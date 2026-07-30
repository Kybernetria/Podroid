#!/usr/bin/env python3
"""Fail-closed static verification for issue #16's disabled management design slice."""

from __future__ import annotations

from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/excp/podroid/management"
TESTS = ROOT / "app/src/test/java/com/excp/podroid/management"
SPEC = ROOT / "management/protocol/v1.md"
ADR = ROOT / "docs/adr/0008-restricted-ssh-host-protocol.md"


def fail(message: str) -> None:
    raise SystemExit(f"host management boundary verification failed: {message}")


def require_tokens(text: str, tokens: tuple[str, ...], area: str) -> None:
    for token in tokens:
        if token not in text:
            fail(f"{area} is missing {token!r}")


def verify_backup(path: Path, sections: tuple[str, ...]) -> None:
    root = ET.parse(path).getroot()
    for section in sections:
        element = root if root.tag == section else root.find(section)
        if element is None:
            fail(f"{path} is missing {section}")
        matches = [
            item for item in element.findall("exclude")
            if item.attrib == {"domain": "file", "path": "host-management/"}
        ]
        if len(matches) != 1:
            fail(f"{path}:{section} must exclude file/host-management/ exactly once")


def main() -> None:
    sources = sorted(MAIN.glob("*.kt"))
    tests = sorted(TESTS.glob("*.kt"))
    if len(sources) < 6 or len(tests) < 6:
        fail("pure management source/test slice is incomplete")
    joined = "\n".join(path.read_text(encoding="utf-8") for path in sources)
    test_text = "\n".join(path.read_text(encoding="utf-8") for path in tests)

    forbidden = {
        "dagger.hilt": "dependency-injection/runtime composition",
        "android.content": "Android runtime hookup",
        "android.app": "Android service/listener hookup",
        "com.excp.podroid.vm": "VM effect hookup",
        "com.excp.podroid.engine": "engine/QMP hookup",
        "com.excp.podroid.service": "service/Binder hookup",
        "ProcessBuilder": "host process execution",
        "ServerSocket": "network listener",
        "sshd": "SSH server library",
        "org.apache.sshd": "Apache SSH server library",
        "net.schmizz.sshj": "SSHJ runtime library",
        "Qmp": "QMP authority",
    }
    for token, description in forbidden.items():
        if token in joined:
            fail(f"{description} found in pure management sources ({token})")

    require_tokens(
        joined,
        (
            'SSH_USERNAME = "podroid-management"',
            'EXEC_COMMAND = "podroid-management-v1"',
            "MAX_REQUEST_BYTES = 4_096",
            "MAX_RESPONSE_BYTES = 16_384",
            "PROTOCOL_DESCRIBE(\"protocol.describe\"",
            "VM_DEFAULT_STATUS(\"vm.default.status\"",
            "VM_DEFAULT_START(\"vm.default.start\"",
            "VM_DEFAULT_STOP(\"vm.default.stop\"",
            "if_generation is mandatory only for mutations",
            "CANONICAL_UUID_V4",
            "ssh-ed25519-cert-v01@openssh.com",
            "ssh-ed25519",
            'READ("read")',
            'OPERATE("operate")',
            'VM_DEFAULT_SSH("vm-default-ssh")',
            "criticalOptions.isNotEmpty()",
            "transportBinding",
            'GUEST_VIRTUAL_HOST = "vm/default/ssh"',
            'GUEST_LOOPBACK_HOST = "127.0.0.1"',
            "GUEST_LOOPBACK_PORT = 9_922",
            "enum class LedgerState { RESERVED, EXECUTING, COMPLETED, REJECTED, INDETERMINATE }",
            "transactionDurably",
            "recoverAfterRestart",
            "AuditStage.PRE_DISPATCH",
            "AUDIT_UNAVAILABLE",
            "RUNTIME_COMPOSITION_NOT_IMPLEMENTED",
            "AUTHENTICATED_PEER_IDENTITY",
        ),
        "Kotlin policy",
    )

    # The operation surface is a closed enum and must not grow silently.
    operation_literals = re.findall(
        r'(?:PROTOCOL_DESCRIBE|VM_DEFAULT_STATUS|VM_DEFAULT_START|VM_DEFAULT_STOP)\("([a-z.]+)"',
        joined,
    )
    if set(operation_literals) != {
        "protocol.describe", "vm.default.status", "vm.default.start", "vm.default.stop"
    } or len(operation_literals) != 4:
        fail("management operation allowlist is not the exact four-operation v1 set")

    require_tokens(
        test_text,
        (
            "framing UTF8 and response bounds",
            "mutations require one non-negative generation",
            "username type CA revocation clock options principals and binding fail closed",
            "every shell PTY env subsystem X11 agent global reverse and unknown request is denied",
            "concurrent duplicates have exactly one reservation winner",
            "restart rejects pre-effect reservations and makes possible effects indeterminate without replay",
            "durable pre-dispatch append is required before permit",
            "audit failure and exhausted capacity deny dispatch",
            "even complete fake evidence cannot produce a listener runtime or effect capability",
            "host management state is excluded from backup cloud restore and device transfer",
        ),
        "management tests",
    )

    spec = SPEC.read_text(encoding="utf-8")
    adr = ADR.read_text(encoding="utf-8")
    require_tokens(
        spec,
        (
            "normative v1 contract",
            "podroid-management-v1",
            "uint32",
            "4,096",
            "16,384",
            "canonical lowercase UUIDv4",
            "if_generation",
            "Response grammar",
            "Exec exit status",
            "Fixed nested guest SSH forwarding",
            "127.0.0.1:9922",
            "Transport identity binding",
            "Atomic idempotency ledger",
            "durable pre-dispatch audit",
            "Compatibility",
            "files/host-management/",
            "RUNTIME_COMPOSITION_NOT_IMPLEMENTED",
        ),
        "normative specification",
    )
    require_tokens(
        adr,
        ("design slice remains disabled", "no SSH library/listener", "INDETERMINATE", "host-management/"),
        "ADR 0008",
    )

    verify_backup(ROOT / "app/src/main/res/xml/backup_rules.xml", ("full-backup-content",))
    verify_backup(
        ROOT / "app/src/main/res/xml/data_extraction_rules.xml",
        ("cloud-backup", "device-transfer"),
    )

    gradle_source = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    if "verifyHostManagementBoundary" not in gradle_source:
        fail("Host-management boundary verifier is not wired into Gradle")
    gradle = gradle_source.lower()
    for dependency in ("apache.sshd", "mina-sshd", "sshj"):
        if dependency in gradle:
            fail(f"SSH runtime dependency unexpectedly present: {dependency}")

    print(
        f"verified disabled Host-management boundary across {len(sources)} Kotlin sources, "
        f"{len(tests)} Kotlin tests, normative spec, ADR, and backup rules"
    )


if __name__ == "__main__":
    main()
