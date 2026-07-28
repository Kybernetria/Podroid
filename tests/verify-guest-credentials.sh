#!/bin/sh
set -eu

REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$REPO_ROOT"

fail() {
    echo "guest credential verification failed: $*" >&2
    exit 1
}

# Construct the retired credential without keeping its plaintext in current
# source or documentation. This value is used only to detect regressions.
RETIRED_PASSWORD=$(printf '\160\157\144\162\157\151\144')

CURRENT_TEXT_FILES='README.md
CLAUDE.md
app/src/main/res/values/strings.xml
app/src/main/res/values-zh/strings.xml
docs/guide/getting-started.html
docs/guide/networking.html
docs/guide/settings.html
docs/guide/packages.html
build-rootfs/build-rootfs.sh'

for source_file in $CURRENT_TEXT_FILES; do
    [ -f "$source_file" ] || fail "missing expected source file: $source_file"
    if grep -Eiq "(default (login|password|credentials)|root password|password:|password[[:space:]]*<code>|密码[：:]|credentials:).{0,80}${RETIRED_PASSWORD}" "$source_file"; then
        fail "retired guest credential is described in $source_file"
    fi
done

DROPBEAR_CONFIG=build-rootfs/files/etc/conf.d/dropbear
[ "$(grep -Ev '^[[:space:]]*(#|$)' "$DROPBEAR_CONFIG")" = 'DROPBEAR_OPTS="-s"' ] || \
    fail "Dropbear must be configured with password authentication disabled"
grep -Fq 'cp /work/files/etc/conf.d/dropbear' build-rootfs/build-rootfs.sh || \
    fail "Dropbear configuration is not copied into the rootfs"
grep -Eq 'openssl rand -hex 48' build-rootfs/build-rootfs.sh || \
    fail "root password is not generated from 48 random bytes"
grep -Eq 'openssl passwd -6 -stdin' build-rootfs/build-rootfs.sh || \
    fail "root password is not stored as a salted SHA-512 hash"
[ "$(grep -Ec '^ROOT_HASH=' build-rootfs/build-rootfs.sh)" -eq 1 ] || \
    fail "root hash must have exactly one assignment"
[ "$(grep -Fc '"$ROOTFS/etc/shadow"' build-rootfs/build-rootfs.sh)" -eq 1 ] || \
    fail "rootfs build must have exactly one explicit shadow write"
grep -Fq 'root:${ROOT_HASH}:' build-rootfs/build-rootfs.sh || \
    fail "the generated random root hash is not written to shadow"
grep -Fq 'exec /bin/login -f root' build-rootfs/files/usr/local/bin/podroid-login || \
    fail "the app-owned guest console does not auto-login root"

# Only these source trees can be packaged as guest credentials. Include
# untracked files because container build contexts include them too. Bound both
# file count and per-file size so a malformed checkout cannot make this test
# consume unbounded resources.
TRACKED_FILES=$(mktemp)
ARTIFACT_LIST=
ARTIFACT_ROOT=
cleanup() {
    rm -f "$TRACKED_FILES"
    [ -z "$ARTIFACT_LIST" ] || rm -f "$ARTIFACT_LIST"
    [ -z "$ARTIFACT_ROOT" ] || rm -rf "$ARTIFACT_ROOT"
}
trap cleanup EXIT HUP INT TERM

find build-rootfs app/src/main/assets -type f ! -name alpine-rootfs.squashfs -print \
    | head -n 2001 > "$TRACKED_FILES"
TRACKED_COUNT=$(wc -l < "$TRACKED_FILES")
[ "$TRACKED_COUNT" -le 2000 ] || fail "credential source scan exceeds 2000 files"

while IFS= read -r tracked_file; do
    [ -f "$tracked_file" ] || continue
    case "$tracked_file" in
        */authorized_keys|*/authorized_keys2|*/id_rsa|*/id_rsa.pub|*/id_dsa|*/id_dsa.pub|*/id_ecdsa|*/id_ecdsa.pub|*/id_ed25519|*/id_ed25519.pub|*/dropbear_*_host_key)
            fail "bundled SSH credential file: $tracked_file"
            ;;
    esac
    FILE_SIZE=$(wc -c < "$tracked_file")
    [ "$FILE_SIZE" -le 16777216 ] || fail "credential source scan file exceeds 16 MiB: $tracked_file"
    if grep -Iq . "$tracked_file" && grep -Eq \
        -- '-----BEGIN (OPENSSH |RSA |EC |DSA )?PRIVATE KEY-----|^(ssh-(rsa|dss|ed25519)|ecdsa-sha2-nistp[0-9]+)[[:space:]][A-Za-z0-9+/=]{40,}' \
        "$tracked_file"; then
        fail "bundled SSH key material in $tracked_file"
    fi
done < "$TRACKED_FILES"

[ "$#" -le 1 ] || fail "usage: $0 [alpine-rootfs.squashfs]"
if [ "$#" -eq 0 ] && [ -f app/src/main/assets/alpine-rootfs.squashfs ]; then
    set -- app/src/main/assets/alpine-rootfs.squashfs
fi
if [ "$#" -eq 1 ]; then
    ARTIFACT=$1
    [ -f "$ARTIFACT" ] || fail "rootfs artifact not found: $ARTIFACT"
    ARTIFACT_SIZE=$(wc -c < "$ARTIFACT")
    [ "$ARTIFACT_SIZE" -le 1073741824 ] || fail "rootfs artifact exceeds 1 GiB inspection bound"
    command -v unsquashfs >/dev/null 2>&1 || fail "unsquashfs is required for artifact inspection"
    command -v openssl >/dev/null 2>&1 || fail "openssl is required for artifact inspection"

    ARTIFACT_ROOT=$(mktemp -d)
    unsquashfs -no-progress -no-xattrs -d "$ARTIFACT_ROOT/rootfs" "$ARTIFACT" \
        etc/shadow etc/conf.d/dropbear etc/issue usr/local/bin/podroid-login >/dev/null

    if awk -F: '$1 != "root" && $2 !~ /^[!*]/ { print $1; bad = 1 } END { exit bad }' \
        "$ARTIFACT_ROOT/rootfs/etc/shadow" >/dev/null; then
        :
    else
        fail "artifact contains a non-root account with a usable password hash"
    fi

    ROOT_HASH=$(awk -F: '$1 == "root" { print $2; exit }' "$ARTIFACT_ROOT/rootfs/etc/shadow")
    case "$ROOT_HASH" in
        '$6$'*) ;;
        *) fail "artifact root account does not have a usable SHA-512 password hash" ;;
    esac
    HASH_SALT=$(printf '%s\n' "$ROOT_HASH" | awk -F'\$' '{ print $3 }')
    RETIRED_HASH=$(openssl passwd -6 -salt "$HASH_SALT" "$RETIRED_PASSWORD")
    [ "$ROOT_HASH" != "$RETIRED_HASH" ] || fail "artifact root hash uses the retired credential"

    [ "$(grep -Ev '^[[:space:]]*(#|$)' "$ARTIFACT_ROOT/rootfs/etc/conf.d/dropbear")" = 'DROPBEAR_OPTS="-s"' ] || \
        fail "artifact Dropbear password authentication is not disabled"
    grep -Fq 'exec /bin/login -f root' "$ARTIFACT_ROOT/rootfs/usr/local/bin/podroid-login" || \
        fail "artifact guest console does not auto-login root"
    grep -Fq 'SSH: public-key authentication only' "$ARTIFACT_ROOT/rootfs/etc/issue" || \
        fail "artifact banner does not describe public-key-only SSH"
    if grep -Eiq "(default (login|password|credentials)|root password|password:|credentials:).{0,80}${RETIRED_PASSWORD}" \
        "$ARTIFACT_ROOT/rootfs/etc/issue"; then
        fail "artifact banner describes the retired credential"
    fi

    ARTIFACT_LIST=$(mktemp)
    unsquashfs -ll "$ARTIFACT" > "$ARTIFACT_LIST"
    ARTIFACT_ENTRY_COUNT=$(wc -l < "$ARTIFACT_LIST")
    [ "$ARTIFACT_ENTRY_COUNT" -le 200000 ] || fail "artifact listing exceeds 200000 entries"
    if grep -Eq '/(authorized_keys2?|id_(rsa|dsa|ecdsa|ed25519)(\.pub)?|dropbear_[^/]*_host_key)$' "$ARTIFACT_LIST"; then
        fail "artifact bundles an SSH authorized key, identity, or host private key"
    fi
fi

echo "Guest credential verification passed${ARTIFACT:+ (source and artifact)}."
