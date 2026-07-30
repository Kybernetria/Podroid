#!/usr/bin/env python3
"""Fail-closed verifier/fetcher for the immutable official Debian cloud-image pin."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

MAX_METADATA_BYTES = 1024 * 1024
MAX_IMAGE_BYTES = 4 * 1024 * 1024 * 1024
MAX_REDIRECTS = 5
EXPECTED_IMAGE = "debian-12-genericcloud-arm64-20250210-2019.raw"
EXPECTED_SHA512 = "102b6205ce89615c3cb652d5e3aaca994ddce573266f2c63492ca6da835ab75bbff747b5674c85ce6a68c7e941cac4f368fae188fcc8b02ca5ce97900f61d38f"
EXPECTED_METADATA_SHA256 = "0780f7aa2553f87b5fb6f11e28c47ca59930075f2977c143ce6d31c1594eda7e"
EXPECTED_BASE = "https://cloud.debian.org/images/cloud/bookworm/20250210-2019/"

class ProvenanceError(ValueError):
    pass


def _origin(url: str) -> str:
    parsed = urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ProvenanceError("upstream URLs and redirect origins must be credential-free HTTPS")
    if parsed.query or parsed.fragment:
        raise ProvenanceError("upstream URLs must not contain query or fragment data")
    port = parsed.port or 443
    if port != 443:
        raise ProvenanceError("upstream URLs must use HTTPS port 443")
    return f"https://{parsed.hostname.lower()}:443"


def load_lock(path: Path) -> dict:
    try:
        raw = path.read_bytes()
        lock = json.loads(raw)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as failure:
        raise ProvenanceError(f"cannot read provenance lock: {failure}") from failure
    expected_root = {"version", "distribution", "release", "codename", "architecture", "build_id", "image", "publisher_metadata"}
    if set(lock) != expected_root:
        raise ProvenanceError("provenance lock root fields are not closed")
    fixed = {
        "version": 1, "distribution": "debian", "release": "12", "codename": "bookworm",
        "architecture": "arm64", "build_id": "20250210-2019",
    }
    if any(lock[key] != value for key, value in fixed.items()):
        raise ProvenanceError("provenance lock selects an unsupported upstream build")
    image = lock["image"]
    metadata = lock["publisher_metadata"]
    if not isinstance(image, dict) or set(image) != {
        "filename", "format", "official_url", "publisher_sha512", "downloaded_sha256", "downloaded_size_bytes"
    }:
        raise ProvenanceError("image provenance fields are not closed")
    if not isinstance(metadata, dict) or set(metadata) != {
        "filename", "official_url", "downloaded_sha256", "detached_signature_url"
    }:
        raise ProvenanceError("metadata provenance fields are not closed")
    if image != {
        "filename": EXPECTED_IMAGE,
        "format": "raw",
        "official_url": EXPECTED_BASE + EXPECTED_IMAGE,
        "publisher_sha512": EXPECTED_SHA512,
        "downloaded_sha256": None,
        "downloaded_size_bytes": None,
    }:
        raise ProvenanceError("image provenance pin differs from the reviewed official raw image")
    if metadata != {
        "filename": "SHA512SUMS",
        "official_url": EXPECTED_BASE + "SHA512SUMS",
        "downloaded_sha256": EXPECTED_METADATA_SHA256,
        "detached_signature_url": None,
    }:
        raise ProvenanceError("publisher metadata pin differs from the downloaded official metadata")
    _origin(image["official_url"])
    _origin(metadata["official_url"])
    return lock


def _read_regular(path: Path, maximum: int):
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        fd = os.open(path, flags)
    except OSError as failure:
        raise ProvenanceError(f"cannot open supplied file {path}: {failure}") from failure
    stream = os.fdopen(fd, "rb")
    info = os.fstat(fd)
    if not stat.S_ISREG(info.st_mode) or info.st_size < 1 or info.st_size > maximum:
        stream.close()
        raise ProvenanceError(f"supplied file is not regular or is outside its byte bound: {path}")
    return stream, info.st_size


def _digest_stream(stream, maximum: int, copy_to=None, deadline_monotonic: float | None = None) -> tuple[int, str, str]:
    sha256 = hashlib.sha256()
    sha512 = hashlib.sha512()
    size = 0
    while True:
        if deadline_monotonic is not None and time.monotonic() >= deadline_monotonic:
            raise ProvenanceError("upstream fetch exceeded its total deadline")
        chunk = stream.read(1024 * 1024)
        if not chunk:
            break
        size += len(chunk)
        if size > maximum:
            raise ProvenanceError("upstream response exceeds its byte bound")
        sha256.update(chunk)
        sha512.update(chunk)
        if copy_to is not None:
            copy_to.write(chunk)
    if size < 1:
        raise ProvenanceError("upstream response is empty")
    return size, sha256.hexdigest(), sha512.hexdigest()


def verify_metadata(path: Path, lock: dict) -> None:
    stream, _ = _read_regular(path, MAX_METADATA_BYTES)
    with stream:
        data = stream.read(MAX_METADATA_BYTES + 1)
    if len(data) > MAX_METADATA_BYTES or hashlib.sha256(data).hexdigest() != lock["publisher_metadata"]["downloaded_sha256"]:
        raise ProvenanceError("downloaded SHA512SUMS does not match the immutable metadata pin")
    entries: dict[str, str] = {}
    try:
        text = data.decode("ascii")
    except UnicodeDecodeError as failure:
        raise ProvenanceError("SHA512SUMS is not ASCII") from failure
    for line in text.splitlines():
        match = re.fullmatch(r"([0-9a-f]{128}) [ *]([^/\x00]+)", line)
        if not match or match.group(2) in entries:
            raise ProvenanceError("SHA512SUMS contains malformed or duplicate entries")
        entries[match.group(2)] = match.group(1)
    if entries.get(EXPECTED_IMAGE) != EXPECTED_SHA512:
        raise ProvenanceError("official SHA512SUMS does not contain the pinned image digest")


def verify_image(path: Path, lock: dict) -> dict:
    stream, expected_size = _read_regular(path, MAX_IMAGE_BYTES)
    with stream:
        size, sha256, sha512 = _digest_stream(stream, MAX_IMAGE_BYTES)
    if size != expected_size or sha512 != lock["image"]["publisher_sha512"]:
        raise ProvenanceError("supplied image does not match the official publisher SHA-512")
    return {"sha256": sha256, "sha512": sha512, "size_bytes": size}


class LockedRedirectHandler(HTTPRedirectHandler):
    def __init__(self, allowed_origins: set[str]):
        super().__init__()
        self.allowed_origins = allowed_origins
        self.redirects = 0

    def redirect_request(self, request, fp, code, message, headers, new_url):
        self.redirects += 1
        if self.redirects > MAX_REDIRECTS or _origin(new_url) not in self.allowed_origins:
            raise ProvenanceError("HTTPS redirect target is not an explicitly approved release input")
        return super().redirect_request(request, fp, code, message, headers, new_url)


def fetch(
    url: str,
    maximum: int,
    allowed_origins: set[str],
    output: Path | None = None,
    expected_sha512: str | None = None,
) -> tuple[bytes | None, dict]:
    handler = LockedRedirectHandler(allowed_origins)
    opener = build_opener(handler)
    request = Request(url, headers={"Accept-Encoding": "identity", "User-Agent": "Podroid-profile-provenance-v1"})
    temporary = None
    destination = None
    response = None
    try:
        response = opener.open(request, timeout=30)
        if response.status != 200 or _origin(response.geturl()) not in allowed_origins:
            raise ProvenanceError("upstream response status/final origin is not approved")
        if response.headers.get("Content-Encoding", "identity").lower() != "identity":
            raise ProvenanceError("encoded upstream responses are not accepted")
        declared = response.headers.get("Content-Length")
        if declared is not None and (not declared.isdigit() or int(declared) < 1 or int(declared) > maximum):
            raise ProvenanceError("upstream Content-Length is outside its byte bound")
        if output is None:
            data = response.read(maximum + 1)
            if len(data) > maximum or not data:
                raise ProvenanceError("upstream response is outside its byte bound")
            facts = {"size_bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(), "sha512": hashlib.sha512(data).hexdigest()}
            return data, facts
        output.parent.mkdir(parents=True, exist_ok=True)
        if output.exists() or output.is_symlink():
            raise ProvenanceError("fetch output already exists; refusing to overwrite it")
        fd, temporary = tempfile.mkstemp(prefix=f".{output.name}.", dir=output.parent)
        destination = os.fdopen(fd, "wb")
        size, sha256, sha512 = _digest_stream(
            response,
            maximum,
            destination,
            deadline_monotonic=time.monotonic() + 2 * 60 * 60,
        )
        if expected_sha512 is not None and sha512 != expected_sha512:
            raise ProvenanceError("fetched image does not match the official publisher SHA-512")
        destination.flush()
        os.fsync(destination.fileno())
        destination.close()
        destination = None
        try:
            os.link(temporary, output)
        except FileExistsError as failure:
            raise ProvenanceError("fetch output already exists; refusing to overwrite it") from failure
        os.unlink(temporary)
        temporary = None
        return None, {"size_bytes": size, "sha256": sha256, "sha512": sha512}
    except (HTTPError, URLError) as failure:
        raise ProvenanceError(f"upstream fetch failed: {failure}") from failure
    finally:
        if response is not None:
            response.close()
        if destination is not None:
            destination.close()
        if temporary is not None:
            try:
                os.unlink(temporary)
            except FileNotFoundError:
                pass


def main() -> int:
    base = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, default=base / "upstream-lock.json")
    parser.add_argument("--metadata", type=Path, default=base / "upstream" / "SHA512SUMS")
    parser.add_argument("--fetch-metadata", action="store_true")
    images = parser.add_mutually_exclusive_group()
    images.add_argument("--image", type=Path)
    images.add_argument("--fetch-image", type=Path, metavar="OUTPUT")
    parser.add_argument("--allow-redirect-origin", action="append", default=[], metavar="HTTPS_ORIGIN")
    args = parser.parse_args()
    try:
        lock = load_lock(args.lock)
        verify_metadata(args.metadata, lock)
        approved = {_origin(lock["image"]["official_url"]), _origin(lock["publisher_metadata"]["official_url"])}
        approved.update(_origin(value) for value in args.allow_redirect_origin)
        if args.fetch_metadata:
            data, _ = fetch(lock["publisher_metadata"]["official_url"], MAX_METADATA_BYTES, approved)
            assert data is not None
            temporary_fd, temporary_name = tempfile.mkstemp()
            try:
                with os.fdopen(temporary_fd, "wb") as temporary_file:
                    temporary_file.write(data)
                verify_metadata(Path(temporary_name), lock)
            finally:
                os.unlink(temporary_name)
        facts = None
        if args.image is not None:
            facts = verify_image(args.image, lock)
        elif args.fetch_image is not None:
            _, fetched = fetch(
                lock["image"]["official_url"],
                MAX_IMAGE_BYTES,
                approved,
                args.fetch_image,
                expected_sha512=lock["image"]["publisher_sha512"],
            )
            facts = fetched
        print(json.dumps({
            "metadata_pin_verified": True,
            "publisher_signature_verified": False,
            "image_verified": facts is not None,
            "image_facts": facts,
        }, sort_keys=True))
        return 0
    except (OSError, ProvenanceError) as failure:
        print(f"error: {failure}", file=os.sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
