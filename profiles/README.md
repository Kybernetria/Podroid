# Profiles

Owns versioned distro-neutral schemas and distro-specific guest realization. Profiles describe bootable guests; they do not own Android lifecycle or imply support merely by existing.

Alpine Direct is the initial known-good profile. Its current inherited build realization and minimal base-image contract are documented in `alpine-direct/`; the closed signed runtime payload contract is `schemas/profile-payload-v1.schema.json`.

`schemas/profile-payload-v2.schema.json` and `debian-cloud/` define a separate signed QEMU UEFI/NoCloud release-preparation contract. V2 has no engine, manager, or repository activation path in this slice and is not a support claim.
