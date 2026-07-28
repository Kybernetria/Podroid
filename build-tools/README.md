# Build Tools

Owns tracked inputs and helper configuration for reproducibly building native dependencies such as QEMU, libslirp, and libusb. Toolchain changes must preserve Android ABI and page-alignment requirements.

Root `build/` is generated and ignored; it is never an authoritative source area. Existing build scripts remain unchanged in Milestone 1.
