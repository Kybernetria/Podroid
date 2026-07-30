# Networking Acceptance Tests

`test_guest_tailscale.py` exercises the packageable guest enrollment, status, and reconnect policy against fake `tailscale`/`tailscaled` processes. It covers strict bounded HTTPS and hostname parsing, mode-0600 one-use-key cleanup, `file:` argv semantics, same-server idempotency, explicit changed-server reauthentication, output redaction, and reboot reconnect behavior.

The Android host and Linux guest have separate node identities and separate trust domains. Host and guest auth keys, node keys, hostnames, state directories, and enrollment metadata must never be reused or copied between them. Guest state exists only at `/var/lib/tailscale` in the persistent guest overlay; no guest identity or key is an APK asset. The future Android bootstrap transport does not read this directory and the guest helpers do not read Android transport state.

`headscale_userspace_integration.sh` is an opt-in, pinned Podman harness for external Headscale enrollment using Tailscale userspace networking. It requires `PODROID_RUN_HEADSCALE_INTEGRATION=1`, Podman, outbound image access, and working rootless container networking. It validates the helper/control-plane interaction only; it is not physical Android, QEMU, AVF, kernel-TUN, or reboot evidence.
