#!/bin/sh
# Remove only obsolete system-layer paths from older appliance images. The
# persistent workload/user data roots under /mnt/persist and /var/lib are
# intentionally not named or traversed here.
set -eu

root=${PODROID_MIGRATION_ROOT:-}
for path in \
    /etc/runlevels/default/podroid-x11 \
    /etc/runlevels/default/docker \
    /etc/runlevels/default/lxc \
    /etc/runlevels/default/dnsmasq.lxcbr0 \
    /etc/runlevels/default/pulseaudio \
    /etc/init.d/podroid-x11 \
    /etc/init.d/pulseaudio \
    /etc/profile.d/podroid-x11.sh \
    /etc/containers/storage.conf \
    /usr/local/bin/podroid-backup \
    /usr/local/bin/podroid-update-stats \
    /usr/share/podroid/logo.png
do
    rm -f -- "${root}${path}"
done
