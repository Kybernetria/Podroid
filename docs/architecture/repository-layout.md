# Repository Layout

Milestone 1 began with tracked ownership markers only. Tickets #6–#15 add VM identity, storage, management, local Binder, controller, persistence, reconciliation, guest networking, controller SSH, and disabled Host-transport slices incrementally. Android runtime slices remain inside the existing `app/` Gradle module; no inherited Android source is moved.

| Area | Intended ownership | Milestone 1 state |
|---|---|---|
| `app/` | Existing logical Android application and current runtime | Authoritative Android runtime; transport contracts/hooks compile here while remaining uncomposed |
| `android-app/platform/` | Android APIs, permissions, networking hooks | README skeleton |
| `android-app/ui/` | Android presentation and user interaction | README skeleton |
| `android-app/vm-service/` | Foreground service and one-active-VM coordination | README skeleton |
| `vm-core/model/` | Distro-neutral VM domain values | `VmId` slice implemented in `app/.../vm/`; module extraction deferred |
| `vm-core/qemu/` | QEMU process adapter | README skeleton |
| `vm-core/qmp/` | Bounded QMP client contract | README skeleton |
| `vm-core/storage/` | Guest image and attachment lifecycle | Instance paths + legacy migration implemented in `app/.../vm/`; module extraction deferred |
| `vm-core/lifecycle/` | Serialized VM state machine | README skeleton |
| `transport/api/` | Transport-neutral authenticated connection interfaces | Ticket #15 Kotlin contracts in `app/.../transport/api`; extraction deferred |
| `transport/tailscale-android/` | Official Tailscale/libtailscale Android adapter | Ticket #15 verified debug packaging + unavailable fail-closed provider boundary |
| `management/protocol/` | Versioned restricted management messages | Ticket #16 frozen v1 specification + pure Kotlin/Rust codecs and policy |
| `management/ssh-server/` | Restricted SSH endpoint; never a host shell | Ticket #16 channel/certificate policy only; no SSH runtime/listener composition |
| `controller/core/` | Shared controller client logic | Preview boundary plus ticket #13 strict direct guest-SSH status/exec/enrollment |
| `controller/phonectl/` | CLI adapter | Ticket #13 strict guest SSH; ticket #16 Host codec remains uncomposed |
| `controller/desktop-ui/` | Slint desktop adapter | Ticket #9 external preview UI; no phone transport or Android packaging |
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
