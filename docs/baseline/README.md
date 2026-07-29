# Preserved upstream baseline

This directory records the source, build inputs, feature boundaries, and measurements for the Podroid baseline used before any reduction work.

## Source anchor

| Field | Baseline |
|---|---|
| Upstream commit | `8ecfefaa3459b7e84c3d6e52c57e8005e289e8e3` |
| Upstream subject | `docs(readme): note container backup, status view, and Downloads sharing` |
| Android application | Podroid (`com.excp.podroid`; debug suffix `.debug`) |
| Guest architecture | AArch64 |
| Guest distribution | Alpine 3.23.x |

Verify the anchor and compare the working source before taking or comparing measurements. The final command permits these baseline documents to live on top of the anchor, but rejects tracked changes elsewhere:

```bash
distrobox enter android-dev -- bash -lc '
  set -euo pipefail
  cd "$(git rev-parse --show-toplevel)"
  expected=8ecfefaa3459b7e84c3d6e52c57e8005e289e8e3
  git cat-file -e "${expected}^{commit}"
  printf "baseline=%s\ncheckout_head=%s\n" "$expected" "$(git rev-parse HEAD)"
  git status --short
  git diff --exit-code "$expected" -- . ":(exclude)docs/baseline/**"
'
```

Untracked files and generated/ignored artifacts must be reviewed separately because the tracked diff check does not prove their absence.

To preserve the source snapshot independently of a remote, create an archive outside the checkout and hash it:

```bash
distrobox enter android-dev -- bash -lc '
  set -euo pipefail
  cd "$(git rev-parse --show-toplevel)"
  commit=8ecfefaa3459b7e84c3d6e52c57e8005e289e8e3
  git archive --format=tar.gz --prefix="podroid-${commit}/" \
    --output="../podroid-${commit}.tar.gz" "$commit"
  sha256sum "../podroid-${commit}.tar.gz"
'
```

The archive preserves tracked source only. It does **not** preserve Git history, downloaded Maven artifacts, SDK/NDK installations, container base images, Alpine repositories, untracked generated VM assets, or native output.

## Baseline evidence

The following evidence was recorded for this source anchor:

- `:app:testDebugUnitTest assembleDebug` completed successfully in the `android-dev` distrobox.
- The resulting **Gradle-only** debug APK was 38,033,502 bytes.
- That APK contained 3 native libraries.
- The source Android manifest declared 12 permissions.
- Device RAM and boot measurements are pending.

“Gradle-only” is an important scope limit: the build did not regenerate QEMU, the kernel/initramfs, or the Alpine rootfs, and the APK excluded the ignored generated QEMU/rootfs assets. It is not evidence that the complete distributable APK can be rebuilt from source.

## Release blocker: fixed root credential

> **RELEASE BLOCKER:** At this baseline, `build-rootfs/build-rootfs.sh` set the guest root password to `[REDACTED RETIRED CREDENTIAL]`, and the guest login banner repeated it. The plaintext is now redacted while this release-blocker record is retained. This was a recorded defect, not recommended usage. Do not treat this baseline as release-ready; a release required an approved first-boot credential/lockout design and verification that no known credential remained.

## Documents

- [Reproducible build procedure and limits](BUILD.md)
- [Feature and dependency inventory](INVENTORY.md)
- [Recorded measurements and measurement template](MEASUREMENTS.md)
