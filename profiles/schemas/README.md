# Profile Schemas

`profile-payload-v1.schema.json` documents the closed signed payload accepted by the in-app v1 strict codec and downloadable-profile repository. The codec remains authoritative and additionally enforces canonical HTTPS origins, exact artifact roles, aggregate byte limits, and signature/trust policy.

Version 1 remains byte-for-byte unchanged and supports only:

- architecture `aarch64`
- boot contract `podroid-direct-v1`
- storage contract `podroid-overlay-ext4-v1`
- health contract `podroid-ready-v1`
- a nonempty unique subset of backends `qemu` and `avf`

The bundled Alpine storage lineage is `podroid-alpine-overlay-v1`. A first downloaded activation may preserve an existing bundled `storage.img` only when its signed `data_compatibility` is exactly that value.

`profile-payload-v2.schema.json` is a strict, separate QEMU-only ARM64 UEFI/NoCloud contract. It fixes four role/format pairs (`cloud-disk`/raw, UEFI code and vars/raw-pflash, CIDATA seed/ISO9660), role-specific limits, cloud readiness, data lineage, and a closed dependency-prefix guest-integration set whose empty value denies every integration. It is signed under the distinct v2 domain separator.

`ProfileRepository`, `VmManager`, and QEMU implement this closed v2 path without widening or changing v1. Release-artifact provenance and physical boot remain separate evidence requirements; the schema/runtime path alone is not such a claim.
