# External controllers

This directory is a standalone Rust Cargo workspace for operator-side clients. It is not linked into, packaged with, or granted authority by the Android Host APK.

Implemented controller components are:

- `core`: validated host/default-VM state, action policy, the narrow `VmServiceBoundary`, ticket #13's bounded direct guest-SSH client, and ticket #16's pure frozen host-management v1 codec/policy;
- `desktop-ui`: a Slint presentation adapter over the non-live preview boundary;
- `phonectl`: the ticket #13 CLI adapter for verified direct guest SSH; and
- `PreviewVmService`: an explicitly non-live, bounded in-memory demonstration boundary for the desktop UI.

## Preview limitation

**The desktop application does not connect to a phone.** It shows `preview-host`, keeps one `default` VM state in process memory, and discards that state when the desktop process exits. Start and Stop mutate only that preview object. The worker owns no QMP, shell, filesystem, arbitrary-command, workload, scheduler, Android Binder, credential, or transport capability.

Ticket #16 prepares the frozen, restricted **Android-host** management v1 codec and a transport-abstract adapter to the same narrow service boundary. It intentionally adds no SSH/network implementation, phone connection, or controller composition change, so the desktop application remains preview-only. A future authenticated transport must replace—not bypass—the preview at the composition edge and enforce its own connection deadline and credentials; Slint remains dependent only on core. See [`core/README.md`](core/README.md) for the exact wire contract.

Ticket #13 guest SSH is deliberately separate: it reaches Dropbear inside the Linux guest directly, verifies an out-of-band host key, and never grants an Android shell or forwarding capability. Its targets, host keys, client keys, authorization, and persisted connection state cannot be reused as Android-host management material. See [`phonectl/README.md`](phonectl/README.md).

Closing the window drops the request sender and allows the worker to exit. It deliberately sends no Stop request. A future live boundary must preserve that behavior so controller loss never acts as a host-service or VM lease.

## Build and test

The workspace pins direct dependencies exactly and commits `Cargo.lock`. On Ubuntu 24.04, install the Slint/winit build prerequisites in the `android-dev` distrobox once:

```sh
distrobox enter android-dev -- sudo apt-get update
distrobox enter android-dev -- sudo apt-get install -y \
  build-essential pkg-config libfontconfig1-dev libxkbcommon-dev \
  libxkbcommon-x11-dev libwayland-dev libx11-xcb-dev \
  libxcb-shape0-dev libxcb-xfixes0-dev
```

Then run all formatting, checking, headless tests, and the desktop binary build from the repository root:

```sh
./controller/scripts/build-in-android-dev.sh
```

To launch the preview from a graphical `android-dev` session:

```sh
distrobox enter android-dev -- bash -lc \
  'cd "$(git rev-parse --show-toplevel)/controller" && cargo run --locked -p podroid-desktop-controller'
```
