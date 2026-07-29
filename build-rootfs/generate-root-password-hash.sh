#!/bin/sh
set -eu

# Generate an unknown build-time root password and emit only its SHA-512 crypt
# hash. The plaintext remains scoped to this process and is never persisted.
root_password=$(openssl rand -hex 48)
[ "${#root_password}" -eq 96 ] || {
    echo "failed to generate 48 random bytes for the root password" >&2
    exit 1
}

root_hash=$(printf '%s\n' "$root_password" | openssl passwd -6 -stdin)
unset root_password
case "$root_hash" in
    '$6$'?*'$'?*) printf '%s\n' "$root_hash" ;;
    *) echo "failed to generate a SHA-512 root password hash" >&2; exit 1 ;;
esac
