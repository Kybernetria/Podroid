#!/usr/bin/env python3
"""Fail-closed static checks for issue #15's disabled Host transport spike."""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TRANSPORT = ROOT / "app/src/main/java/com/excp/podroid/transport"


def fail(message: str) -> None:
    raise SystemExit(f"host transport boundary verification failed: {message}")


def main() -> None:
    sources = sorted(TRANSPORT.rglob("*.kt"))
    if not sources:
        fail("Kotlin transport sources are absent")
    joined = "\n".join(path.read_text(encoding="utf-8") for path in sources)

    forbidden = {
        "android.net.VpnService": "VpnService",
        "bindProcessToNetwork": "process-wide network binding",
        "setProcessDefaultNetwork": "legacy process-wide network binding",
        "android.content.Intent": "runtime service hookup",
        "dagger.hilt": "runtime dependency injection hookup",
        "com.excp.podroid.vm": "VM contract dependency",
        "com.excp.podroid.engine": "engine/QMP dependency",
        "com.excp.podroid.service": "runtime service dependency",
        "HostRequestDispatcher": "management dispatch dependency",
        "PortForwardRepository": "forwarding dependency",
        "ProcessBuilder": "shell/process dependency",
    }
    for token, description in forbidden.items():
        if token in joined:
            fail(f"{description} found ({token})")

    provider = (TRANSPORT / "tailscale/LibtailscaleSpikeProvider.kt").read_text(encoding="utf-8")
    for token in (
        "ProviderAvailability.UNAVAILABLE",
        "DETERMINISTIC_CANCELLATION",
        "AUTHENTICATED_PEER_IDENTITY",
        "PER_NETWORK_SOCKET_BINDING",
        "PER_NETWORK_DNS",
    ):
        if token not in provider:
            fail(f"disabled capability evidence missing: {token}")

    raw = (TRANSPORT / "tailscale/RawLibtailscaleBindings.kt").read_text(encoding="utf-8")
    for token in (
        "interface RawLibtailscaleBindings",
        "newServer",
        "configure",
        "start",
        "listen",
        "accept",
        "dial",
        "remoteAddress",
        "read",
        "write",
        "loopback",
        "close",
    ):
        if token not in raw:
            fail(f"raw binding operation missing: {token}")

    hooks = (TRANSPORT / "android/AndroidNetworkHooks.kt").read_text(encoding="utf-8")
    for token in ("registerDefaultNetworkCallback", "getAllByName", "network.bindSocket"):
        if token not in hooks:
            fail(f"Android network hook missing: {token}")

    contracts = (TRANSPORT / "api/TransportContracts.kt").read_text(encoding="utf-8")
    for token in ("OpenHostTransportRequest", "TransportDeadline", "TransportCancellation"):
        if token not in contracts:
            fail(f"bounded lifecycle contract missing: {token}")
    if "HostTransportIdentity" not in contracts or "GuestWorkloadIdentity" not in contracts:
        fail("Host and guest identity domain separation is missing")

    state = (TRANSPORT / "state/HostTransportState.kt").read_text(encoding="utf-8")
    for token in ('resolve("host-transport")', 'resolve("instances")', "ATOMIC_MOVE", "schemaVersion"):
        if token not in state:
            fail(f"strict Host-only atomic state evidence missing: {token}")

    for relative in ("app/src/main/res/xml/backup_rules.xml", "app/src/main/res/xml/data_extraction_rules.xml"):
        if 'path="host-transport/"' not in (ROOT / relative).read_text(encoding="utf-8"):
            fail(f"Host identity backup exclusion missing from {relative}")

    pin = json.loads((ROOT / "third_party/libtailscale-pin.json").read_text(encoding="utf-8"))
    if pin.get("integrationStatus") != "debug-packaged-provider-unavailable":
        fail("official source pin does not record unavailable provider status")

    print(f"verified disabled Host transport boundary across {len(sources)} Kotlin sources")


if __name__ == "__main__":
    main()
