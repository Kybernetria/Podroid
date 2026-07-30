# Profile Schemas

`profile-payload-v1.schema.json` documents the closed signed payload accepted by the in-app strict codec. The codec remains authoritative and additionally enforces canonical HTTPS origins, exact artifact roles, aggregate byte limits, and signature/trust policy.

Version 1 supports only:

- architecture `aarch64`
- boot contract `podroid-direct-v1`
- storage contract `podroid-overlay-ext4-v1`
- health contract `podroid-ready-v1`
- a nonempty unique subset of backends `qemu` and `avf`

The bundled Alpine storage lineage is `podroid-alpine-overlay-v1`. A first downloaded activation may preserve an existing bundled `storage.img` only when its signed `data_compatibility` is exactly that value.
