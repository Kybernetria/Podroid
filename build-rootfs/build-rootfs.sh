#!/bin/sh
set -eu
ROOTFS=/work/rootfs
MINIMAL_PACKAGES=/work/minimal-packages.txt
RESOLVED_PACKAGES_LOCK=/work/resolved-packages.lock
RUNLEVELS_LOCK=/work/runlevels.lock
MAX_EXPLICIT_PACKAGES=32
MAX_RESOLVED_PACKAGES=128

# ALPINE_VERSION comes from the Dockerfile ENV (full release like 3.23.4).
# Strip the patch component to get the major branch (e.g. 3.23) used in repo URLs.
: "${ALPINE_VERSION:?ALPINE_VERSION must be set (e.g. 3.23.4)}"
ALPINE_BRANCH="${ALPINE_VERSION%.*}"

mkdir -p "$ROOTFS/etc/apk"
cat > "$ROOTFS/etc/apk/repositories" <<EOF
https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_BRANCH}/main
https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_BRANCH}/community
EOF

# Parse the reviewed package manifest once at the image boundary. Reject blank,
# malformed, or oversized manifests before apk performs network or disk effects.
set --
while IFS= read -r package || [ -n "$package" ]; do
    case "$package" in
        ''|*[!a-z0-9+_.=-]*)
            echo "invalid minimal package entry: $package" >&2
            exit 1
            ;;
    esac
    set -- "$@" "$package"
    [ "$#" -le "$MAX_EXPLICIT_PACKAGES" ] || {
        echo "minimal package manifest exceeds $MAX_EXPLICIT_PACKAGES entries" >&2
        exit 1
    }
done < "$MINIMAL_PACKAGES"
[ "$#" -gt 0 ] || { echo "minimal package manifest is empty" >&2; exit 1; }

apk -X "https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_BRANCH}/main" \
    -X "https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_BRANCH}/community" \
    --keys-dir "$ROOTFS/etc/apk/keys" \
    -U --root "$ROOTFS" --initdb add "$@"

# Fail the build if repository drift changes any reviewed package identity.
# Every lock row is: name, version, architecture, Alpine origin, aports commit.
# apk has already authenticated each package with the pinned Alpine signing keys.
resolved_packages=$(awk 'BEGIN { RS=""; FS="\n" }
    {
        p=v=a=o=c=""
        for (i=1; i<=NF; i++) {
            if ($i ~ /^P:/) p=substr($i,3)
            else if ($i ~ /^V:/) v=substr($i,3)
            else if ($i ~ /^A:/) a=substr($i,3)
            else if ($i ~ /^o:/) o=substr($i,3)
            else if ($i ~ /^c:/) c=substr($i,3)
        }
        if (p != "") print p "\t" v "\t" a "\t" o "\t" c
    }' "$ROOTFS/lib/apk/db/installed" | LC_ALL=C sort)
resolved_count=$(printf '%s\n' "$resolved_packages" | wc -l)
[ "$resolved_count" -le "$MAX_RESOLVED_PACKAGES" ] || {
    echo "resolved package closure exceeds $MAX_RESOLVED_PACKAGES entries" >&2
    exit 1
}
[ "$resolved_packages" = "$(cat "$RESOLVED_PACKAGES_LOCK")" ] || {
    echo "resolved package provenance differs from reviewed lock" >&2
    printf '%s\n' "$resolved_packages" >&2
    exit 1
}

# Keep root usable for public-key SSH without shipping a known password. This
# executes the same narrowly scoped entropy generator exercised by verification.
ROOT_HASH=$(/work/generate-root-password-hash.sh)
sed -i "s|^root:[^:]*:|root:${ROOT_HASH}:|" "$ROOTFS/etc/shadow"

# Strip docs/man/locale to shrink squashfs.
rm -rf "$ROOTFS/usr/share/man" "$ROOTFS/usr/share/doc" \
       "$ROOTFS/usr/share/locale" "$ROOTFS/usr/share/info"

# Copy the backend-neutral OpenRC boot contract into the rootfs.
for svc in bootstrap network resize ready vsock hostd downloads migrate tailscale-reconnect; do
    cp "/work/files/etc/init.d/podroid-$svc" "$ROOTFS/etc/init.d/"
done
chmod +x "$ROOTFS/etc/init.d/podroid-"*

# Console/getty and bounded guest Tailscale helpers.
mkdir -p "$ROOTFS/usr/local/bin" "$ROOTFS/usr/local/libexec"
for helper in resize login getty tailscale-enroll tailscale-status tailscale-reconnect; do
    cp "/work/files/usr/local/bin/podroid-$helper" "$ROOTFS/usr/local/bin/"
done
cp /work/files/usr/local/libexec/podroid-tailscale-common "$ROOTFS/usr/local/libexec/"
chmod 0755 "$ROOTFS/usr/local/libexec/podroid-tailscale-common"

# Native backend/control helpers are COPY'd from the builder stage. The guest
# host CLIs are argv[0]-dispatch symlinks onto one multi-call binary.
chmod +x "$ROOTFS/usr/local/bin/podroid-vsock-agent" \
         "$ROOTFS/usr/local/bin/podroid-hostd" \
         "$ROOTFS/usr/local/bin/podroid-overlay-normalize" \
         "$ROOTFS/usr/local/bin/podroid-migrate-safe"
for cli in notify forward open power headless server; do
    ln -sf podroid-hostd "$ROOTFS/usr/local/bin/podroid-$cli"
done
chmod +x "$ROOTFS/usr/local/bin/podroid-"*

mkdir -p "$ROOTFS/etc/conf.d"
cp /work/files/etc/conf.d/podroid  "$ROOTFS/etc/conf.d/"
cp /work/files/etc/conf.d/dropbear "$ROOTFS/etc/conf.d/"
cp /work/files/etc/conf.d/tailscale "$ROOTFS/etc/conf.d/"

# tailscale-openrc creates this guest-owned state directory. It is the only
# persistent Tailscale location; enrollment material is never seeded here.
[ -d "$ROOTFS/var/lib/tailscale" ] && [ ! -L "$ROOTFS/var/lib/tailscale" ] || {
    echo "tailscale package omitted its regular state directory" >&2
    exit 1
}
chmod 0750 "$ROOTFS/var/lib/tailscale"
for forbidden_state in tailscaled.state podroid-enrollment; do
    [ ! -e "$ROOTFS/var/lib/tailscale/$forbidden_state" ] || {
        echo "rootfs must not seed Tailscale identity or enrollment state" >&2
        exit 1
    }
done

# The AVF vsock control channel is the only initial forward. SSH and user rules
# are applied by Android at runtime.
mkdir -p "$ROOTFS/etc/podroid"
cp /work/files/etc/podroid/forwards.conf "$ROOTFS/etc/podroid/forwards.conf"
chmod 0644 "$ROOTFS/etc/podroid/forwards.conf"

# Migration anchor, immutable bounded index, runner, and idempotent hooks.
mkdir -p "$ROOTFS/etc/podroid/migrations"
cp /work/files/etc/podroid/migrations/README "$ROOTFS/etc/podroid/migrations/README"
cp /work/files/etc/podroid/migrations/index "$ROOTFS/etc/podroid/migrations/index"
cp /work/files/etc/podroid/migrations/31.sh "$ROOTFS/etc/podroid/migrations/31.sh"
cp /work/files/usr/local/bin/podroid-migrate-runner "$ROOTFS/usr/local/bin/podroid-migrate-runner"
chmod 0644 "$ROOTFS/etc/podroid/migrations/index"
chmod 0755 "$ROOTFS/etc/podroid/migrations/31.sh" "$ROOTFS/usr/local/bin/podroid-migrate-runner"
printf '%s\n' "${SYSTEM_VERSION:-0}" > "$ROOTFS/etc/podroid/system-version"
chmod 0644 "$ROOTFS/etc/podroid/system-version"

cp /work/files/etc/inittab "$ROOTFS/etc/inittab"
cp /work/files/etc/rc.conf "$ROOTFS/etc/rc.conf"

mkdir -p "$ROOTFS/etc/profile.d"
cp /work/files/etc/profile.d/podroid-color.sh "$ROOTFS/etc/profile.d/"
chmod 0644 "$ROOTFS/etc/profile.d/podroid-color.sh"

# Host identity and public-key-only remote-access guidance.
echo "podroid" > "$ROOTFS/etc/hostname"
echo "127.0.0.1 localhost podroid" > "$ROOTFS/etc/hosts"
echo "::1 localhost ip6-localhost" >> "$ROOTFS/etc/hosts"
cat > "$ROOTFS/etc/issue" <<'EOF'
Welcome to Podroid (Alpine \S)
Kernel \r on \m (\l)

  App-owned guest console: automatic local root session
  SSH: public-key authentication only
  Provision SSH keys at: /root/.ssh/authorized_keys

EOF

# Reconstruct every runlevel executed by inittab from the reviewed source lock.
# Removing apk's runlevel tree first makes package drift unable to add an
# implicit boot or shutdown service. The x86_64 builder cannot chroot into the
# AArch64 image to invoke rc-update.
rm -rf "$ROOTFS/etc/runlevels"
mkdir -p "$ROOTFS/etc/runlevels/sysinit" "$ROOTFS/etc/runlevels/boot" \
         "$ROOTFS/etc/runlevels/default" "$ROOTFS/etc/runlevels/shutdown"
seen_sysinit=0
seen_boot=0
seen_default=0
seen_shutdown=0
empty_sysinit=0
empty_boot=0
empty_default=0
empty_shutdown=0
while IFS=' ' read -r runlevel entry target extra || [ -n "${runlevel:-}" ]; do
    case "${runlevel:-}" in
        ''|'#'*) continue ;;
        sysinit) seen_sysinit=1 ;;
        boot) seen_boot=1 ;;
        default) seen_default=1 ;;
        shutdown) seen_shutdown=1 ;;
        *) echo "unknown reviewed runlevel: $runlevel" >&2; exit 1 ;;
    esac
    [ -z "${extra:-}" ] || { echo "malformed runlevel lock entry" >&2; exit 1; }
    if [ "$entry" = - ] && [ "$target" = - ]; then
        case "$runlevel" in
            sysinit) [ "$empty_sysinit" = 0 ] || { echo "duplicate empty runlevel declaration: $runlevel" >&2; exit 1; }; empty_sysinit=1 ;;
            boot) [ "$empty_boot" = 0 ] || { echo "duplicate empty runlevel declaration: $runlevel" >&2; exit 1; }; empty_boot=1 ;;
            default) [ "$empty_default" = 0 ] || { echo "duplicate empty runlevel declaration: $runlevel" >&2; exit 1; }; empty_default=1 ;;
            shutdown) [ "$empty_shutdown" = 0 ] || { echo "duplicate empty runlevel declaration: $runlevel" >&2; exit 1; }; empty_shutdown=1 ;;
        esac
        continue
    fi
    case "$runlevel" in
        sysinit) [ "$empty_sysinit" = 0 ] ;;
        boot) [ "$empty_boot" = 0 ] ;;
        default) [ "$empty_default" = 0 ] ;;
        shutdown) [ "$empty_shutdown" = 0 ] ;;
    esac || { echo "runlevel mixes empty and populated declarations: $runlevel" >&2; exit 1; }
    case "$entry:$target" in
        *[!a-zA-Z0-9_.+-]*:*|*:*[!a-zA-Z0-9_./+-]*)
            echo "malformed runlevel entry: $runlevel $entry $target" >&2
            exit 1
            ;;
    esac
    [ "$target" = "/etc/init.d/$entry" ] || {
        echo "runlevel target is not its exact init script: $runlevel $entry" >&2
        exit 1
    }
    [ -f "$ROOTFS/etc/init.d/$entry" ] && [ ! -L "$ROOTFS/etc/init.d/$entry" ] || {
        echo "required regular init script missing: $entry" >&2
        exit 1
    }
    [ ! -e "$ROOTFS/etc/runlevels/$runlevel/$entry" ] || {
        echo "duplicate runlevel entry: $runlevel $entry" >&2
        exit 1
    }
    ln -s "$target" "$ROOTFS/etc/runlevels/$runlevel/$entry"
done < "$RUNLEVELS_LOCK"
[ "$seen_sysinit$seen_boot$seen_default$seen_shutdown" = 1111 ] || {
    echo "runlevel lock does not declare every inittab runlevel" >&2
    exit 1
}
