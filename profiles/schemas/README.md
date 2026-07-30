# Profile Schemas

`profile-payload-v1.schema.json` documents the closed signed payload accepted by the in-app v1 strict codec and downloadable-profile repository. The codec remains authoritative and additionally enforces canonical HTTPS origins, exact artifact roles, aggregate byte limits, and signature/trust policy.

Version 1 remains byte-for-byte unchanged and supports only:

- architecture `aarch64`
- boot contract `podroid-direct-v1`
- storage contract `podroid-overlay-ext4-v1`
- health contract `podroid-ready-v1`
- a nonempty unique subset of backends `qemu` and `avf`

The bundled Alpine storage lineage is `podroid-alpine-overlay-v1`. A first downloaded activation may preserve an existing bundled `storage.img` only when its signed `data_compatibility` is exactly that value.

`profile-payload-v2.schema.json` is a strict, separate release-preparation contract for QEMU-only ARM64 UEFI/NoCloud guests. It fixes four role/format pairs (`cloud-disk`/raw, UEFI code and vars/raw-pflash, CIDATA seed/ISO9660), role-specific limits, cloud readiness, data lineage, and a closed typed guest-integration set whose empty value denies every integration. It is signed under the distinct v2 domain separator.

The current engine, `VmManager`, and `ProfileRepository` intentionally accept only v1. Adding a v2 schema and verifier does not activate cloud profiles or widen the v1 contract.
