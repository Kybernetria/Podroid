# Credits & Acknowledgements

Podroid stands on the shoulders of an enormous amount of open-source work. The list below covers the upstream projects whose code, designs, fonts, or themes are shipped inside the APK, along with the major Android-side dependencies. Every component remains under its own upstream license; see [LICENSE](LICENSE) for Podroid's own license (GPL v2.0).

If something you maintain is included here and we missed you, please open an issue or a pull request and we will fix it.

---

## Virtual machine stack

These projects make up the actual Linux system that runs inside Podroid.

### Linux kernel
Podroid ships a custom build of the upstream Linux kernel (currently 7.0.10) configured specifically for OCI-compatible containers under QEMU TCG.

- Project: https://kernel.org
- License: GNU General Public License v2.0
- Maintainers: Linus Torvalds and the Linux kernel community

### Alpine Linux
The guest userspace is a slightly customized Alpine Linux 3.23 root filesystem.

- Project: https://alpinelinux.org
- License: per-package (see Alpine's individual package licenses)

### Optional orchestrators
The minimal guest does **not** ship Podman, Docker, LXC, crun, netavark, aardvark-dns, fuse-overlayfs, slirp4netns, or shadow-utils. Its custom kernel retains the prerequisites for a guest owner to install and configure an orchestrator later. Optional software installed into persistent guest storage remains under its own upstream license and is not part of the APK's reviewed package closure.

### System & init
- **busybox**: multi-call binary providing the initramfs userland and `/sbin/init` (https://busybox.net, GPL-2.0)
- **OpenRC**: service manager running as PID 1 inside the VM (https://github.com/OpenRC/openrc, BSD-2-Clause)
- **Dropbear SSH**: small SSH server (https://matt.ucc.asn.au/dropbear/dropbear.html, MIT-style)
- **iproute2** and Alpine's minimal bridge support: networking utilities maintained by the Linux and Alpine communities

### Inherited Android X11 viewer
The inherited Android RFB/audio client code remains during staged refactoring, but the guest does not ship TigerVNC/Xvnc, PulseAudio, desktop or font packages. It creates no display/audio listener or automatic forward, so the viewer has no bundled guest endpoint.

---

## Emulation

### QEMU
QEMU provides the machine emulation that makes everything else possible. Podroid currently builds against QEMU 11.0.0, cross-compiled for arm64 Android.

- Project: https://www.qemu.org
- Repository: https://gitlab.com/qemu-project/qemu
- License: GNU General Public License v2.0 (and later)
- Original author: Fabrice Bellard
- Contributors: https://www.qemu.org/contributors/

### libslirp
SLIRP is QEMU's user-mode network stack. Podroid links it into the QEMU binary statically.

- Project: https://gitlab.freedesktop.org/slirp/libslirp
- License: BSD-3-Clause

### libucontext
Bionic does not implement the System V `ucontext.h` API, which QEMU coroutines depend on. Podroid links against Ariadne Conill's portable libucontext to fill the gap.

- Project: https://github.com/kaniini/libucontext
- License: ISC

### Limbo PC Emulator
Limbo pioneered running QEMU on Android years ago and solved many of the platform-specific issues we no longer have to. Although Podroid is a fresh codebase with a different UI and pipeline, the lineage of "QEMU on Android" traces back to Max Kastanas's work.

- Repository: https://github.com/limboemu/limbo
- License: GNU General Public License v2.0
- Author: Max Kastanas (max2idea)

---

## Terminal & UI

### Termux Terminal Emulator
The terminal layer (xterm-256color emulator, escape-sequence parsing, mouse tracking, color theme parsing) is the Termux project's `terminal-view` and `terminal-emulator` libraries, consumed via JitPack as `com.github.termux:terminal-view:0.118.1`. Podroid rebuilds `libtermux.so` locally for 16 KB page alignment.

- Project: https://github.com/termux/termux-app
- License: GNU General Public License v3.0
- Maintainers: Fredrik Fornwall and the Termux community

### Color themes
Podroid ships 122 terminal color schemes covering most of the popular ecosystem (Dracula, Nord, Solarized, Tokyo Night, Catppuccin, Gruvbox, Monokai, the base16 family, and more). The themes are sourced from the [**Gogh**](https://github.com/Gogh-Co/Gogh) project's curated collection (MIT). Individual color palette designers are credited within each `.properties` file.

### Bundled fonts

| Font                                                                                | License             |
| ----------------------------------------------------------------------------------- | ------------------- |
| [JetBrains Mono](https://www.jetbrains.com/mono/)                                   | OFL-1.1             |
| [Fira Code](https://github.com/tonsky/FiraCode)                                     | OFL-1.1             |
| [Cascadia Code](https://github.com/microsoft/cascadia-code)                         | OFL-1.1             |
| [Source Code Pro](https://github.com/adobe-fonts/source-code-pro)                   | OFL-1.1             |
| [Hack](https://github.com/source-foundry/Hack)                                      | MIT + Bitstream     |
| [Iosevka](https://github.com/be5invis/Iosevka)                                      | OFL-1.1             |
| [Victor Mono](https://github.com/rubjo/victor-mono)                                 | OFL-1.1             |
| [Monofur](https://www.fontsquirrel.com/fonts/monofur)                               | Free for personal & commercial use |
| [Anonymous Pro](https://www.marksimonson.com/fonts/view/anonymous-pro)              | OFL-1.1             |
| [DejaVu Sans Mono](https://dejavu-fonts.github.io/)                                 | DejaVu / Bitstream Vera |
| [Liberation Mono](https://github.com/liberationfonts/liberation-fonts)              | OFL-1.1             |
| [Ubuntu Mono](https://design.ubuntu.com/font/)                                      | UFL-1.0             |
| [Terminus](https://terminus-font.sourceforge.net/)                                  | OFL-1.1             |

---

## Android application

### Language & libraries
- **Kotlin** by JetBrains s.r.o. (https://kotlinlang.org, Apache-2.0)
- **Jetpack Compose** by Google LLC (Apache-2.0)
- **AndroidX** (Lifecycle, Navigation, DataStore, ViewModel, Activity Compose) by Google LLC (Apache-2.0)
- **Hilt / Dagger** by Google LLC and the Dagger Authors (Apache-2.0)
- **Material 3** by Google LLC (Apache-2.0)
- **kotlinx.coroutines** by JetBrains (Apache-2.0)

### Build toolchain
- **Android Gradle Plugin, Android SDK, Android NDK r27c** by Google LLC
- **Gradle** by Gradle, Inc. (Apache-2.0)
- **Docker** for the cross-build pipelines used by the kernel, initramfs, rootfs, and QEMU stages

---

## Podroid

Podroid itself is developed by **[ExTV](https://github.com/ExTV)** and contributors.

- Repository: https://github.com/ExTV/Podroid
- Website: https://extv.github.io/Podroid/
- License: GNU General Public License v2.0 (or later)

If you have contributed code, themes, fonts, or bug reports and would like to be listed here, please open an issue or a pull request.

---

## Reporting missing or incorrect attribution

We take attribution seriously. If you believe your work is shipped in Podroid but not properly credited, or if any information above is incorrect, please [open an issue](https://github.com/ExTV/Podroid/issues/new) and we will get it fixed quickly.
