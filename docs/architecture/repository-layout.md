# Repository Layout

Milestone 1 adds tracked ownership markers only. No new directory is included by Gradle, and no inherited source is moved.

| Area | Intended ownership | Milestone 1 state |
|---|---|---|
| `app/` | Existing logical Android application and current runtime | Authoritative, unchanged |
| `android-app/platform/` | Android APIs, permissions, networking hooks | README skeleton |
| `android-app/ui/` | Android presentation and user interaction | README skeleton |
| `android-app/vm-service/` | Foreground service and one-active-VM coordination | README skeleton |
| `vm-core/model/` | Distro-neutral VM domain values | README skeleton |
| `vm-core/qemu/` | QEMU process adapter | README skeleton |
| `vm-core/qmp/` | Bounded QMP client contract | README skeleton |
| `vm-core/storage/` | Guest image and attachment lifecycle | README skeleton |
| `vm-core/lifecycle/` | Serialized VM state machine | README skeleton |
| `transport/api/` | Transport-neutral authenticated connection interfaces | README skeleton |
| `transport/tailscale-android/` | Official Tailscale/libtailscale Android adapter | README skeleton |
| `management/protocol/` | Versioned restricted management messages | README skeleton |
| `management/ssh-server/` | Restricted SSH endpoint; never a host shell | README skeleton |
| `controller/core/` | Shared controller client logic | README skeleton |
| `controller/phonectl/` | CLI adapter | README skeleton |
| `controller/desktop-ui/` | Slint desktop adapter | README skeleton |
| `profiles/schemas/` | Versioned profile schemas | README skeleton |
| `profiles/alpine-direct/` | Initial known-good Alpine profile | README skeleton |
| `profiles/debian-cloud/` | Deferred profile placeholder, not support | README skeleton |
| `termux-integration/` | Optional Termux companion backend | README skeleton |
| `build-tools/` | Tracked QEMU/libslirp/libusb build inputs | Existing area, documented |
| `build/` | Generated output only | Ignored; never source |
| `tests/{android,vm,networking,swarm}/` | Cross-area acceptance evidence | README skeletons |
| `docs/{architecture,adr,threat-model,device-matrix}/` | Architecture and operational evidence | Documentation |

## Migration rules

- `app/` remains the logical `android-app` and the only Android app module during migration.
- Preserve package IDs and existing `engine/` and `service/` paths until a separate change provides compatible moves.
- A skeleton directory is not a Gradle module, API promise, or implemented feature.
- Later extraction must be incremental, retain upstream buildability, and receive its own ADR when it changes ownership or a public contract.
- Native source inputs belong under tracked source/build-tool areas. Root `build/` remains generated and ignored.
