# Third-party source pins

## libtailscale

[`libtailscale/`](libtailscale/) is the official [`tailscale/libtailscale`](https://github.com/tailscale/libtailscale) repository pinned as a Git submodule. Clone it with:

```bash
distrobox enter android-dev -- bash -lc '
  cd /path/to/Podroid
  git submodule update --init --recursive
'
```

The pin and reviewed API limitations are recorded in [`libtailscale-pin.json`](libtailscale-pin.json). It is source-only scaffolding for [ticket #15](https://github.com/Kybernetria/Podroid/issues/15); no Android binary is linked into the Host APK yet.

The pinned public C API wraps `tsnet` and provides lifecycle, `listen`, `dial`, `ControlURL`, auth-key, state-directory, and remote-address operations. It does **not** itself provide an AAR, Android `ConnectivityManager`/DNS/active-network hooks, or authenticated peer identity. Those requirements must be implemented and verified behind `transport/api` before remote host mutations are enabled. The guest continues to use a separate ordinary Linux `tailscaled` identity.
