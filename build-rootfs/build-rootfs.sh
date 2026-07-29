#!/bin/sh
set -eu
ROOTFS=/work/rootfs
MINIMAL_PACKAGES=/work/minimal-packages.txt
MAX_EXPLICIT_PACKAGES=32

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
        ''|*[!a-z0-9+_.-]*)
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

# Keep root usable for public-key SSH without shipping a known password. This
# executes the same narrowly scoped entropy generator exercised by verification.
ROOT_HASH=$(/work/generate-root-password-hash.sh)
sed -i "s|^root:[^:]*:|root:${ROOT_HASH}:|" "$ROOTFS/etc/shadow"

# Strip docs/man/locale to shrink squashfs.
rm -rf "$ROOTFS/usr/share/man" "$ROOTFS/usr/share/doc" \
       "$ROOTFS/usr/share/locale" "$ROOTFS/usr/share/info"

# Copy the backend-neutral OpenRC boot contract into the rootfs.
for svc in bootstrap network resize ready vsock hostd downloads migrate; do
    cp "/work/files/etc/init.d/podroid-$svc" "$ROOTFS/etc/init.d/"
done
chmod +x "$ROOTFS/etc/init.d/podroid-"*

# Console/getty helpers.
mkdir -p "$ROOTFS/usr/local/bin"
for helper in resize login getty; do
    cp "/work/files/usr/local/bin/podroid-$helper" "$ROOTFS/usr/local/bin/"
done

# Native backend/control helpers are COPY'd from the builder stage. The guest
# host CLIs are argv[0]-dispatch symlinks onto one multi-call binary.
chmod +x "$ROOTFS/usr/local/bin/podroid-vsock-agent" \
         "$ROOTFS/usr/local/bin/podroid-hostd" \
         "$ROOTFS/usr/local/bin/podroid-overlay-normalize"
for cli in notify forward open power headless server; do
    ln -sf podroid-hostd "$ROOTFS/usr/local/bin/podroid-$cli"
done
chmod +x "$ROOTFS/usr/local/bin/podroid-"*

mkdir -p "$ROOTFS/etc/conf.d"
cp /work/files/etc/conf.d/podroid  "$ROOTFS/etc/conf.d/"
cp /work/files/etc/conf.d/dropbear "$ROOTFS/etc/conf.d/"

# The AVF vsock control channel is the only initial forward. SSH and user rules
# are applied by Android at runtime.
mkdir -p "$ROOTFS/etc/podroid"
cp /work/files/etc/podroid/forwards.conf "$ROOTFS/etc/podroid/forwards.conf"
chmod 0644 "$ROOTFS/etc/podroid/forwards.conf"

# Migration anchor and versioned idempotent hooks.
mkdir -p "$ROOTFS/etc/podroid/migrations"
cp /work/files/etc/podroid/migrations/README "$ROOTFS/etc/podroid/migrations/README"
cp /work/files/etc/podroid/migrations/31.sh "$ROOTFS/etc/podroid/migrations/31.sh"
chmod 0755 "$ROOTFS/etc/podroid/migrations/31.sh"
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

# Set runlevels via direct symlinks: the x86_64 builder cannot chroot into the
# AArch64 image to invoke rc-update. No appliance/container/X11 services belong
# in this minimal guest runlevel.
mkdir -p "$ROOTFS/etc/runlevels/default" "$ROOTFS/etc/runlevels/boot"
for svc in podroid-migrate podroid-bootstrap podroid-network podroid-resize dropbear podroid-vsock podroid-downloads podroid-hostd podroid-ready; do
    [ -e "$ROOTFS/etc/init.d/$svc" ] || {
        echo "required init script missing: $svc" >&2
        exit 1
    }
    ln -sf "/etc/init.d/$svc" "$ROOTFS/etc/runlevels/default/$svc"
done

# Initramfs and Podroid services own these responsibilities; remove noisy stock
# defaults from the image runlevels.
for svc in hwclock swclock urandom networking sysctl bootmisc syslog; do
    rm -f "$ROOTFS/etc/runlevels/boot/$svc" "$ROOTFS/etc/runlevels/default/$svc"
done
