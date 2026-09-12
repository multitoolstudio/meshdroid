// MeshdroidFS.h: adapts portduino's filesystem to what MeshCore expects.
//
// Force-included ahead of everything by the env's
//   -include variants/meshdroid_ch341/MeshdroidFS.h
// so that FILESYSTEM is already defined when src/helpers/IdentityStore.h is
// reached. Its #if chain covers ESP32, RP2040, NRF52 and STM32 with no #else,
// so without this every file that touches storage fails to compile.
//
// framework-portduino already provides the filesystem, in
// cores/portduino/FS/. fs::File derives from Stream and has read, write, seek,
// size, close, name, isDirectory and openNextFile; fs::FS has open, exists,
// remove, rename, mkdir and rmdir; and PortduinoFS is a ready global instance
// whose VFS root portduino's own main() mounts before setup() runs.
//
// So this is a wrapper, not an implementation. It exists for exactly two
// differences:
//
//   1. MeshCore's non-embedded paths call open(path, mode, create) with three
//      arguments, matching ESP32's fs::FS. portduino's open takes two.
//   2. DataStore::formatFileSystem() needs something to call. There is no
//      partition to reformat when the directory belongs to the app.
//
// An earlier version of this file reimplemented the whole filesystem over
// POSIX, and was additionally named PortduinoFS.h, which shadowed portduino's
// own header of that name on the include path and broke its main.cpp. Hence the
// name here, and hence delegating rather than duplicating.
#pragma once

// C++ only. The build force-includes this into every translation unit and some
// dependencies are C, such as the BME680 driver's bme68x.c.
#ifdef __cplusplus

#include <FS.h>
#include <FS/PortduinoFS.h>

#include <string>
#include <vector>

class MeshdroidFS {
  fs::FS* _fs;

public:
  explicit MeshdroidFS(fs::FS& fs) : _fs(&fs) {}

  // The three-argument form MeshCore uses. "create" means make the file when it
  // is absent, which portduino's two-argument open does not do for "r".
  //
  // Parent directories are created too. portduino's VFS is a real directory
  // tree and will not make them, while SPIFFS on ESP32 is flat and never needed
  // them, so MeshCore assumes a path like "/identity/_main.id" can just be
  // opened. Belt and braces alongside the identity_store.begin() call that
  // patch 0001 enables: if a future MeshCore version adds another nested path,
  // this handles it rather than failing the write silently.
  File open(const char* path, const char* mode = FILE_READ, bool create = false) {
    if (create) {
      makeParents(path);
      if (!_fs->exists(path)) {
        File made = _fs->open(path, FILE_WRITE);
        if (made) made.close();
      }
    }
    return _fs->open(path, mode);
  }

  // Creates every directory component of a file path, ignoring the last.
  // mkdir on an existing directory fails harmlessly, so the result is not
  // checked; what matters is that the leaf's parent exists afterwards.
  void makeParents(const char* path) {
    if (path == NULL) return;
    std::string p(path);
    size_t at = p.find('/', 1);
    while (at != std::string::npos) {
      _fs->mkdir(p.substr(0, at).c_str());
      at = p.find('/', at + 1);
    }
  }

  bool exists(const char* path) { return _fs->exists(path); }
  bool remove(const char* path) { return _fs->remove(path); }
  bool rename(const char* from, const char* to) { return _fs->rename(from, to); }
  bool mkdir(const char* path) { return _fs->mkdir(path); }
  bool rmdir(const char* path) { return _fs->rmdir(path); }

  // Refuses, deliberately. DataStore::formatFileSystem() calls removeAll()
  // instead; silently reformatting a directory we do not own would be worse
  // than either alternative.
  bool format() { return false; }

  // Deletes every regular file at the VFS root. This is what a factory reset
  // means here: identity, prefs, contacts and channels all go, and peers
  // holding this node as a contact will see a stranger afterwards. That matches
  // what formatting the partition does on an embedded board.
  //
  // Names are collected before anything is deleted, since removing entries
  // while walking a directory handle is not safe.
  bool removeAll() {
    File root = _fs->open("/", FILE_READ);
    if (!root || !root.isDirectory()) return false;

    std::vector<std::string> doomed;
    for (File entry = root.openNextFile(); entry; entry = root.openNextFile()) {
      if (entry.isDirectory()) continue;
      const char* n = entry.name();
      if (n == NULL || n[0] == '\0') continue;
      doomed.push_back(n[0] == '/' ? std::string(n) : std::string("/") + n);
    }
    root.close();

    bool ok = true;
    for (size_t i = 0; i < doomed.size(); i++) {
      if (!_fs->remove(doomed[i].c_str())) ok = false;
    }
    return ok;
  }
};

#define FILESYSTEM MeshdroidFS

// The instance MeshCore's DataStore and IdentityStore write through. Defined in
// target.cpp, wrapping portduino's global PortduinoFS.
extern MeshdroidFS AppFS;

#endif  // __cplusplus
