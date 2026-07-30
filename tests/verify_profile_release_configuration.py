#!/usr/bin/env python3
"""Validate the complete non-secret downloadable-profile release trust snapshot."""
from __future__ import annotations

import argparse
import base64
import re
import sys
from urllib.parse import urlsplit

SAFE_ID = re.compile(r"^[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?$")
DNS = re.compile(r"^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
ED25519_X509_PREFIX = bytes.fromhex("302a300506032b6570032100")
MAX_INT64_DOMAIN = 9223372036854775806


def validate(key_id: str, public_key_base64: str, trust_epoch: str, origins_csv: str) -> list[str]:
    errors: list[str] = []
    if not key_id or not public_key_base64 or not trust_epoch or not origins_csv:
        return ["release profile trust configuration must provide all four fields"]
    if len(key_id) > 64 or not SAFE_ID.fullmatch(key_id):
        errors.append("signing key id is not a bounded lowercase safe identifier")
    try:
        decoded = base64.b64decode(public_key_base64, validate=True)
        if base64.b64encode(decoded).decode("ascii") != public_key_base64:
            raise ValueError("noncanonical")
        if len(decoded) != 44 or not decoded.startswith(ED25519_X509_PREFIX):
            raise ValueError("not Ed25519 SubjectPublicKeyInfo")
    except (ValueError, base64.binascii.Error):
        errors.append("public key is not canonical Ed25519 X.509 base64")
    if not trust_epoch.isascii() or not trust_epoch.isdigit() or trust_epoch.startswith("0"):
        errors.append("trust epoch is not a canonical positive integer")
    else:
        epoch = int(trust_epoch)
        if epoch < 1 or epoch > MAX_INT64_DOMAIN:
            errors.append("trust epoch is outside the supported bound")
    origins = origins_csv.split(",")
    if len(origins) not in range(1, 17) or len(set(origins)) != len(origins):
        errors.append("canonical origins must be a unique nonempty set of at most 16")
    for origin in origins:
        try:
            parsed = urlsplit(origin)
            if (
                parsed.scheme != "https"
                or parsed.hostname is None
                or parsed.hostname != parsed.hostname.lower()
                or not DNS.fullmatch(parsed.hostname)
                or parsed.port != 443
                or parsed.username is not None
                or parsed.password is not None
                or parsed.path
                or parsed.query
                or parsed.fragment
                or origin != f"https://{parsed.hostname}:443"
            ):
                raise ValueError("noncanonical origin")
        except (ValueError, UnicodeError):
            errors.append("profile origin is not canonical HTTPS with explicit port 443")
            break
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--key-id", required=True)
    parser.add_argument("--public-key-base64", required=True)
    parser.add_argument("--trust-epoch", required=True)
    parser.add_argument("--origins", required=True)
    args = parser.parse_args()
    errors = validate(args.key_id, args.public_key_base64, args.trust_epoch, args.origins)
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    print("Release downloadable-profile trust configuration is valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
