#!/usr/bin/env python3
"""
Generates patches/0003-libch341-pipe-recovery.patch against the libch341 copy in
a MeshCore checkout's PlatformIO libdeps.

The problem it fixes, established by reading libpinedio-usb.c:

usb_transfer()'s error path cancels our own transfers and does nothing about the
device. A command the CH341 has already accepted is still answered, and that
answer arrives after we have stopped listening for it, so the next transfer reads
the previous transfer's reply. The sizes then disagree: a six byte status reply
read into a four byte SPI read is exactly LIBUSB_TRANSFER_OVERFLOW, which is the
symptom that starts the cascade. Nothing in the library corrects the offset
afterwards, so the node logs transfer errors until the dongle is physically
reconnected, which is the only thing that resets the endpoints.

Four edits:

  1. CH341_USB_TIMEOUT 1000 -> 5000. A timeout is what starts the desync, and a
     second is marginal on a phone where the process can be descheduled for
     longer than that with a transfer pending.
  2. pinedio_resync(), which clears the halt condition on both bulk endpoints and
     drains whatever the device still has queued.
  3. A call to it on the error path, after the cancellations have completed and
     before the mutex is released.
  4. The pin poll thread stops dying on its first failed read. As written it
     exits permanently, which leaves DIO1 edge detection dead for the life of the
     process: the node runs, listens, and never hears the radio again. That makes
     any recovery pointless, because the pipe comes back and nothing is watching
     the interrupt pin.

Run this once against a checkout whose libdeps have been populated and patched
(that is, after one run of build-meshcored-android.sh), then commit the patch.
The build script applies it like every other patch.

Every edit anchors on exact stripped lines and asserts each appears once, so an
upstream change makes this fail loudly instead of corrupting a file. Indentation
is copied from the anchor, so tabs or spaces both work.

Usage:
    ./make-patch-0003.py /home/john/meshcore-firmware
"""

import difflib
import shutil
import sys
from pathlib import Path

PATCH_NAME = "0003-libch341-pipe-recovery.patch"
MARKER = "MESHDROID_PATCH_0003_R1"

TIMEOUT_OLD = "#define CH341_USB_TIMEOUT 1000"

TIMEOUT_NEW = """/* Meshdroid: a second is comfortable on a desktop and marginal on a phone, where
 * this process can be descheduled for longer than that with a transfer pending.
 * A transfer that times out while the device answers it anyway is what
 * desynchronises the pipe, so the timeout is the first line of defence. Five
 * seconds costs nothing while things are working. */
#define CH341_USB_TIMEOUT 5000"""

RESYNC_ANCHOR = "static int32_t usb_transfer(struct pinedio_inst *inst, const char *func, unsigned int writecnt, unsigned int readcnt,"

RESYNC_HELPER = """/* Meshdroid: proof that patch 0003 reached the build, greped out of the finished
 * binary by build-meshcored-android.sh. */
__attribute__((used)) static const char MESHDROID_PATCH_0003[] = "MESHDROID_PATCH_0003_R1";

#define PINEDIO_RESYNC_MAX_PACKETS 64
#define PINEDIO_RESYNC_DRAIN_MS 20

/* Meshdroid: resynchronise the bulk pipes after a failed transfer.
 *
 * The error path below cancels our own transfers, which does nothing about the
 * device: a command it has already accepted is still answered, and that answer
 * arrives after we have stopped listening for it. The next transfer then reads
 * the previous one's reply, the sizes disagree, and nothing here corrects the
 * offset, so the caller sees transfer errors until the dongle is reconnected.
 *
 * Clearing the halt condition resets the data toggle on each endpoint, and the
 * drain reads whatever the device still has queued and throws it away. Both are
 * synchronous and bounded. Called with usb_access_mutex held and after every
 * cancellation has completed, so no asynchronous transfer of ours is in flight.
 *
 * LIBUSB_ERROR_NO_DEVICE means the dongle has left the bus, where there is
 * nothing to resynchronise and every further call would just fail slowly. */
static void pinedio_resync(struct pinedio_inst *inst) {
  if (inst == NULL || inst->handle == NULL) return;

  if (libusb_clear_halt(inst->handle, CH341_WRITE_EP) == LIBUSB_ERROR_NO_DEVICE) return;
  if (libusb_clear_halt(inst->handle, CH341_READ_EP) == LIBUSB_ERROR_NO_DEVICE) return;

  uint8_t drain[CH341_PACKET_LENGTH];
  int dropped = 0;
  for (int i = 0; i < PINEDIO_RESYNC_MAX_PACKETS; i++) {
    int len = 0;
    int ret = libusb_bulk_transfer(inst->handle, CH341_READ_EP, drain, sizeof(drain), &len,
                                   PINEDIO_RESYNC_DRAIN_MS);
    if (ret != 0 || len == 0) break;
    dropped += len;
  }
  fprintf(stderr, "pinedio: resynchronised the CH341 pipes, dropped %d stale bytes\\n", dropped);
}
"""

ERR_PATH_ANCHOR = "/* Wait for cancellations to complete. */"
ERR_PATH_UNLOCK = "if (lock) {"

ERR_PATH_CALL = """/* Meshdroid: the device may still be holding a reply to a command it has
 * already accepted. Clear it out here, while nothing of ours is in flight and
 * the mutex is still held, or every later transfer reads the wrong one. */
pinedio_resync(inst);
"""

POLL_LOOP_ANCHOR = "while (!should_exit) {"
POLL_DECL = "int fail_streak = 0;"

POLL_FAIL_ANCHOR = 'fprintf(stderr, "Failed to get input, res: %d\\n", ret);'

POLL_FAIL_NEW = """if (ret < 0) {
\tinst->in_error = true;
\tfprintf(stderr, "Failed to get input, res: %d\\n", ret);
\t/* Meshdroid: upstream sets should_exit here, so one failed read ends this
\t * thread for the life of the process. DIO1 edge detection never comes back,
\t * and the node keeps running and listening while it can no longer hear the
\t * radio. Keep polling instead: a pipe that pinedio_resync() has cleared
\t * starts answering again, and there is nothing else to notice that.
\t *
\t * The exit request still has to be honoured on this path, or pinedio_deinit()
\t * would block in pthread_join() forever while the thread spins here. */
\tif (inst->pin_poll_thread_exit) should_exit = true;
\tif (++fail_streak >= PINEDIO_POLL_MAX_FAILS) {
\t\tfprintf(stderr, "pinedio: pin poll thread giving up after %d consecutive failures\\n", fail_streak);
\t\tshould_exit = true;
\t}
\t/* Upstream continues without sleeping, which is only safe because it also
\t * exits. Sleep here so a failing read cannot spin the CPU. */
\tplatform_sleep(1000 / 30);
\tcontinue;
}
fail_streak = 0;"""

POLL_MAX_FAILS_DEFINE = """/* Meshdroid: consecutive failed reads before the pin poll thread gives up. At the
 * 30 Hz poll rate this is a few seconds of a link that is not coming back, which
 * is long enough for pinedio_resync() to have had several chances. */
#define PINEDIO_POLL_MAX_FAILS 50
"""


def find_one(lines, text, label):
    """Index of the single line whose stripped content equals text."""
    hits = [i for i, l in enumerate(lines) if l.strip() == text]
    if len(hits) != 1:
        raise SystemExit(f"!! {label}: anchor matched {len(hits)} lines, expected 1.\n"
                         f"   libch341 has probably changed. Nothing was written.")
    return hits[0]


def indent_of(line):
    return line[:len(line) - len(line.lstrip())]


def reindent(block, pad):
    """
    Apply the anchor's indentation to a block written with one tab per level.

    The unit is taken from the anchor rather than assumed: this file descends
    from flashrom and is tab indented, but a reformatted upstream would not be.
    """
    unit = "\t" if "\t" in pad else "  "
    out = []
    for l in block.split("\n"):
        if l == "":
            out.append("")
            continue
        depth = len(l) - len(l.lstrip("\t"))
        out.append(pad + unit * depth + l.lstrip("\t"))
    return out


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2

    mc = Path(sys.argv[1]).resolve()
    libdeps = mc / ".pio" / "libdeps" / "android-arm64"
    if not libdeps.is_dir():
        print(f"{libdeps} does not exist. Run build-meshcored-android.sh once first.")
        return 1

    candidates = [d for d in libdeps.iterdir() if d.is_dir() and "ch341" in d.name.lower()]
    if len(candidates) != 1:
        print(f"expected one ch341 library directory under {libdeps}, found {len(candidates)}")
        return 1
    src = candidates[0] / "libpinedio-usb.c"
    if not src.is_file():
        print(f"{src} does not exist")
        return 1

    text = src.read_text()

    if MARKER in text:
        print(f">> {src.name} already carries {MARKER}; nothing to do.")
        print("   Restore it first if you want to regenerate:")
        print(f"   rm -rf {candidates[0]} && (cd {mc} && pio pkg install -e android-arm64)")
        return 0

    # Generated against the file with patch 0002 already applied, so that it lands
    # at zero offset when the build script applies them in order.
    if "PINEDIO_FD_PATCH_R2" not in text:
        print(f"!! {src} does not carry patch 0002's marker.")
        print("   Run build-meshcored-android.sh once so the patches are applied,")
        print("   then run this again. A patch generated against the pristine file")
        print("   would apply at an offset, which the build treats as suspect.")
        return 1

    before = text.splitlines()
    lines = list(before)

    # 1. the transfer timeout
    i = find_one(lines, TIMEOUT_OLD, "CH341_USB_TIMEOUT")
    lines[i:i + 1] = TIMEOUT_NEW.split("\n")
    print(">> CH341_USB_TIMEOUT: 1000 -> 5000")

    # 2. the resync helper and the poll thread's failure budget
    i = find_one(lines, RESYNC_ANCHOR, "usb_transfer")
    lines[i:i] = (POLL_MAX_FAILS_DEFINE + "\n" + RESYNC_HELPER).split("\n")
    print(">> pinedio_resync(): added")

    # 3. call it on the error path
    i = find_one(lines, ERR_PATH_ANCHOR, "cancellation wait")
    j = next((k for k in range(i, len(lines)) if lines[k].strip() == ERR_PATH_UNLOCK), None)
    if j is None:
        raise SystemExit("!! error path: no unlock found after the cancellation wait.")
    pad = indent_of(lines[j])
    lines[j:j] = [pad + l if l else "" for l in ERR_PATH_CALL.split("\n")]
    print(">> pinedio_resync(): called on the error path")

    # 4. the pin poll thread
    i = find_one(lines, POLL_LOOP_ANCHOR, "poll loop")
    pad = indent_of(lines[i])
    lines[i:i] = [pad + POLL_DECL]
    print(">> pin poll thread: fail_streak declared")

    k = find_one(lines, POLL_FAIL_ANCHOR, "poll failure branch")
    if lines[k - 2].strip() != "should_exit = true;" or lines[k - 1].strip() != "inst->in_error = true;":
        raise SystemExit("!! poll failure branch: the two lines above the fprintf are not the "
                         "ones this patch expects. Nothing was written.")
    if lines[k + 1].strip() != "continue;" or lines[k + 2].strip() != "}":
        raise SystemExit("!! poll failure branch: the two lines below the fprintf are not the "
                         "ones this patch expects. Nothing was written.")
    pad = indent_of(lines[k - 3])
    lines[k - 3:k + 3] = reindent(POLL_FAIL_NEW, pad)
    print(">> pin poll thread: survives a failed read")

    diff = list(difflib.unified_diff(before, lines,
                                     fromfile="a/libpinedio-usb.c",
                                     tofile="b/libpinedio-usb.c",
                                     lineterm=""))
    if not diff:
        print("!! diff is empty; nothing was changed")
        return 1

    here = Path(__file__).resolve().parent
    out = here / "patches" / PATCH_NAME
    out.parent.mkdir(exist_ok=True)
    out.write_text("\n".join(diff) + "\n")
    print(f">> wrote {out} ({len(diff)} lines)")
    print(">> the checkout was not modified; the build script applies the patch")
    return 0


if __name__ == "__main__":
    sys.exit(main())
