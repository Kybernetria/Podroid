# External controllers

This directory is a standalone Rust Cargo workspace for operator-side clients. It is not linked into, packaged with, or granted authority by the Android Host APK.

Ticket #9 implements only:

- `core`: validated host/default-VM state, action policy, and the narrow `VmServiceBoundary` (`refresh`, `start`, `stop`);
- `desktop-ui`: a Slint presentation adapter; and
- `PreviewVmService`: an explicitly non-live, bounded in-memory demonstration boundary.

## Preview limitation

**The desktop application does not connect to a phone.** It shows `preview-host`, keeps one `default` VM state in process memory, and discards that state when the desktop process exits. Start and Stop mutate only that preview object. The worker owns no QMP, shell, filesystem, arbitrary-command, workload, scheduler, Android Binder, credential, or transport capability.

The authenticated, restricted remote-management protocol and its controller adapter belong to ticket #16. That adapter will implement the same narrow service boundary after protocol authentication, authorization, input bounds, deadlines, and compatibility rules are specified. It must replace—not bypass—the preview at the composition edge; Slint remains dependent only on core.

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
