# Debian 12 UEFI/NoCloud Profile Release Inputs

This directory contains reproducible release inputs for signed profile payload v2. Podroid now has a closed QEMU UEFI/NoCloud runtime path for this contract; these inputs still do not claim that the unresolved release artifacts have physically booted. The v1 direct-kernel Alpine contract remains supported unchanged.

## Closed v2 contract

The signed v2 payload is QEMU-only and fixes:

- `aarch64`, UEFI, and NoCloud;
- one `cloud-disk` in raw format (no qcow2 runtime parser), one `uefi-code` raw pflash image, one writable-copy source `uefi-vars-template` raw pflash image, and one `nocloud-seed` `iso9660-cidata` image;
- role-specific and aggregate byte ceilings;
- `podroid-cloud-disk-v1` storage and `podroid-debian-12-genericcloud-v1` data lineage;
- the `PODROID_CLOUD_READY_V1` serial readiness marker; and
- a typed guest-integration dependency prefix. The committed Debian seed declares the empty prefix, so terminal, resize, Host bridge, and Downloads behavior is denied rather than inferred.

V2 signatures cover `com.excp.podroid.vm-profile.v2\0 || exact_payload_bytes`. The v1 domain and deterministic bytes are unchanged. V2 uses explicit envelope version 2 plus `signing_domain=com.excp.podroid.vm-profile.v2`; the closed envelope discriminator selects the verifier before payload parsing, so payload bytes cannot choose or confuse their signing domain.

## Deterministic credential-free CIDATA

`nocloud/` contains the complete reviewed NoCloud source set. It creates no user, password, password hash, authorized key, private key, token, or remote seed. DHCP is selected locally. The closed `vendor-data` command emits one exact CR/LF-delimited readiness line to the ARM64 serial console from cloud-init's final module; no prefix, suffix, embedded marker, or alternate command is accepted.

Build and inspect with only Python's standard library:

```sh
python3 profiles/debian-cloud/build_nocloud_seed.py build --output /tmp/podroid-debian-cidata.iso
python3 profiles/debian-cloud/build_nocloud_seed.py inspect \
  --image /tmp/podroid-debian-cidata.iso \
  --source profiles/debian-cloud/nocloud
```

The builder uses a fixed ISO layout and timestamps, the exact `CIDATA` volume label, normalized root filenames, bounded regular-file reads, reviewed SHA-256 hashes, and an exact decoded-document allowlist. Inspection rejects noncanonical ISO bytes, duplicate/unknown paths, YAML overrides, arbitrary modules or commands, credential material, token fields, and HTTP(S)/NoCloud seed references. Identical reviewed sources produce identical ISO bytes. Cloud serial output is retained only in the app's bounded in-memory boot tail and is omitted from persistent console capture.

The same NoCloud source/tool format is usable by Debian and Ubuntu cloud-init images, but only the Debian image below is pinned and reviewed in this slice. An Ubuntu release requires its own immutable official provenance lock and boot evidence.

## Official Debian provenance

`upstream-lock.json` selects exactly:

- Debian 12 (`bookworm`) ARM64 dated build `20250210-2019`;
- official URL `https://cloud.debian.org/images/cloud/bookworm/20250210-2019/debian-12-genericcloud-arm64-20250210-2019.raw`; and
- publisher SHA-512 `102b6205ce89615c3cb652d5e3aaca994ddce573266f2c63492ca6da835ab75bbff747b5674c85ce6a68c7e941cac4f368fae188fcc8b02ca5ce97900f61d38f`.

`upstream/SHA512SUMS` is the downloaded official dated metadata, itself pinned by SHA-256. The dated directory publishes no detached signature for that file; the tooling does not imply otherwise. HTTPS plus the committed metadata digest records what was reviewed, while Podroid profile signing remains mandatory before distribution.

Verify the lock and committed metadata:

```sh
python3 profiles/debian-cloud/verify_upstream.py
```

Verify an independently downloaded image and derive release-time SHA-256/size facts:

```sh
python3 profiles/debian-cloud/verify_upstream.py --image /path/to/debian-12-genericcloud-arm64-20250210-2019.raw
```

The lock intentionally records `null` for downloaded image SHA-256 and size because the 3 GiB raw image was not downloaded for this source change. Those facts must be computed from the fully downloaded, SHA-512-verified bytes; they must not be inferred from HTTP metadata.

The verifier can fetch with `--fetch-image OUTPUT`, but the official image URL currently redirects to a Debian mirror. Redirect targets fail closed unless their exact HTTPS origin is supplied with `--allow-redirect-origin`. Selecting and recording an acceptable immutable mirror/origin policy is an unresolved release input, not a silent trust expansion.

## Unresolved release inputs

A release cannot be produced from this directory alone. It still requires reviewed immutable HTTPS locations and hashes for QEMU ARM64 UEFI code/vars templates, a canonical Podroid artifact origin for all four artifacts, successful boot/recovery evidence, an approved redirect/mirror policy, and an offline Podroid Ed25519 release signature. No private signing key or signed profile is stored here.
