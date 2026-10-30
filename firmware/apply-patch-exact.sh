# Source this file from a firmware build script, then use apply_patch_exact.
# patch's -F0 disables fuzzy context matching but still permits shifted hunks.
# Reject offsets too, since a shifted patch can compile while changing the wrong code.
patch_reverse_check_succeeds() {
  local target="$1"
  local patch_file="$2"
  local output

  if ! output=$(patch -p1 -F0 -R --dry-run --force -d "$target" < "$patch_file" 2>&1); then
    return 1
  fi
  # Reverse-check offsets are expected because the patch's additions changed
  # the file's line count. Forward applications below still reject every offset.
  case "$output" in
    *fuzz*|*Reversed*|*previously\ applied*|*Skipping*) return 1 ;;
  esac
  return 0
}

apply_patch_exact() {
  local target="$1"
  local patch_file="$2"
  local output

  if ! output=$(patch -p1 -F0 -d "$target" --force --no-backup-if-mismatch < "$patch_file" 2>&1); then
    printf '%s\n' "$output" >&2
    return 1
  fi
  printf '%s\n' "$output"
  case "$output" in
    *offset*|*fuzz*|*Reversed*|*previously\ applied*|*Skipping*)
      echo "!! $(basename "$patch_file") did not apply cleanly at the exact context; regenerate it against this upstream revision." >&2
      return 1
      ;;
  esac
}
