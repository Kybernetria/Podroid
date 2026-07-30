# phonectl

`phonectl` is the CLI adapter over `controller-core`. Ticket #13 implements direct SSH to the **Linux guest**; this is separate from the restricted Android-host management endpoint planned by ticket #16.

## Trust setup

Before connecting, copy the guest's ED25519 host public key and SHA-256 fingerprint over an out-of-band trusted path, such as the app-owned local console. Store exactly one OpenSSH `known_hosts` line for the target and provide the exact 43-character unpadded OpenSSH `SHA256:...` fingerprint. The client rejects TOFU, changed keys, password/keyboard-interactive authentication, PTYs, agent/X11 forwarding, local commands, multiplexing, and every SSH forwarding mode.

For each operation, core copies the validated identity and `known_hosts` material through stable open file descriptors into a random mode-0700 private directory. Both `ssh-keygen` verification and `ssh` use that immutable operation snapshot; the directory is removed when the operation ends. This prevents a pathname replacement between verification and connection.

The controller private key must be a regular, non-symlink file with no group/other permissions. Guest SSH credentials must not be reused for the future Android host-management identity. MVP commands authenticate as guest `root`; this includes the guest root console's existing access to bounded bridge helpers such as `podroid-forward` and `podroid-power`. It does not provide an Android shell, arbitrary Android filesystem access, or QMP, but the key is a privileged guest credential.

## Commands

```bash
common=(
  --host 100.64.0.10
  --port 22
  --identity "$HOME/.ssh/podroid-guest"
  --known-hosts "$HOME/.config/podroid/guest-known-hosts"
  --host-key-sha256 'SHA256:...'
)

phonectl guest status "${common[@]}"
phonectl guest exec "${common[@]}" -- uname -a
phonectl guest enroll "${common[@]}" \
  --login-server https://headscale.example.test \
  --hostname phone-one-guest \
  --auth-key-file "$HOME/.config/podroid/one-use.key"
```

`guest exec` intentionally executes an operator-supplied command **inside the guest**. It never executes an Android process. There is no SSH direct-TCPIP, local/remote/dynamic forwarding, QMP, Android shell, or arbitrary Android filesystem API. Guest-root commands can intentionally invoke the inherited, bounded guest bridge helpers documented above.

The one-use Headscale key is streamed over verified SSH stdin and never appears in local or remote process arguments. Once safely acquired, the key is overwritten through its stable descriptor, synced, and unlinked after every enrollment outcome—including invalid enrollment parameters, cancellation, and remote failure. Cleanup failure is reported as failure. An input rejected before safe acquisition, such as a symlink or broadly readable file, is not modified.

## Runtime bounds

OpenSSH runs with a clean environment and fixed `/usr/bin/ssh` and `/usr/bin/ssh-keygen` paths. Connections and commands have cancellable absolute operation deadlines; custom command deadlines must be in the range `(0, 5 minutes]`. Ordinary status/exec uses 60 seconds, while enrollment uses 120 seconds to cover the guest helper's serialized lock and bounded `tailscale up`. Stdout is capped at 1 MiB and stderr at 64 KiB. Missing exit status, nonzero exit, cancellation, output overflow, and timeout fail closed with stable errors that do not echo remote diagnostics.

An enrollment timeout or cancellation after stdin was sent is potentially indeterminate: the guest may have committed state before the SSH process ended. The local one-use key is still consumed and removed. Query `guest status` before creating a new one-use key; never retry the old key blindly.
