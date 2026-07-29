#!/bin/sh
# Host regression suite for fail-closed legacy-overlay normalization.
set -eu
cd "$(dirname "$0")"
command -v cc >/dev/null || { echo "FAIL: need a host C compiler" >&2; exit 1; }
command -v setfattr >/dev/null || { echo "FAIL: need attr/setfattr" >&2; exit 1; }
command -v getfattr >/dev/null || { echo "FAIL: need attr/getfattr" >&2; exit 1; }

T=$(mktemp -d)
BIN="$T/podroid-overlay-normalize"
MARKER_PAYLOAD='podroid-overlay-normalize-v1'
EXPECTED_MARKER="$T/expected-normalized-marker"
trap 'rm -rf "$T"' EXIT HUP INT TERM
printf '%s\n' "$MARKER_PAYLOAD" > "$EXPECTED_MARKER"
cc -O2 -Wall -Wextra -Werror -DPODROID_NORMALIZE_TESTING \
    -o "$BIN" podroid-overlay-normalize.c

new_fixture() {
    root=$1
    mkdir -p "$root/upper/sub" "$root/work/index/deep" "$root/.podroid"
    printf 'realdata\n' > "$root/upper/realfile"
    : > "$root/upper/metacopyfile"
    printf 'index\n' > "$root/work/index/deep/entry"
    setfattr -n user.overlay.metacopy -v y "$root/upper/metacopyfile"
    setfattr -n user.overlay.redirect -v /old "$root/upper/sub"
}

assert_unmarked() {
    [ ! -e "$1/.podroid/normalized" ] && [ ! -L "$1/.podroid/normalized" ] || {
        echo "FAIL: failed pre-publication normalization published a marker: $1" >&2
        exit 1
    }
}

assert_exact_marker() {
    root=$1
    [ -f "$root/.podroid/normalized" ] && [ ! -L "$root/.podroid/normalized" ] || {
        echo "FAIL: regular normalized marker missing" >&2
        exit 1
    }
    cmp -s "$root/.podroid/normalized" "$EXPECTED_MARKER" || {
        echo "FAIL: normalized marker payload is not exact" >&2
        exit 1
    }
}

assert_cleanup_complete() {
    root=$1
    [ -f "$root/upper/realfile" ] || { echo "FAIL: real file removed" >&2; exit 1; }
    [ ! -e "$root/upper/metacopyfile" ] || { echo "FAIL: metacopy file kept" >&2; exit 1; }
    [ -d "$root/upper/sub" ] || { echo "FAIL: redirected directory removed" >&2; exit 1; }
    if getfattr -n user.overlay.redirect "$root/upper/sub" >/dev/null 2>&1; then
        echo "FAIL: redirect xattr kept" >&2
        exit 1
    fi
    [ ! -e "$root/work/index" ] || { echo "FAIL: index kept" >&2; exit 1; }
}

assert_normalized() {
    assert_cleanup_complete "$1"
    assert_exact_marker "$1"
}

# Every cleanup operation class is injected as a hard failure. No incomplete
# run may publish the marker, and retrying a partially-normalized tree must
# complete successfully.
for operation in lstat open read xattr remove unlink rmdir path close cleanup-sync; do
    root="$T/fail-$operation"
    new_fixture "$root"
    if PODROID_NORMALIZE_NS=user.overlay. PODROID_NORMALIZE_FAIL="$operation" \
        "$BIN" "$root" >/dev/null 2>&1; then
        echo "FAIL: injected $operation failure was ignored" >&2
        exit 1
    fi
    assert_unmarked "$root"
    PODROID_NORMALIZE_NS=user.overlay. "$BIN" "$root"
    assert_normalized "$root"
done

# A legacy empty or old regular marker is not evidence of completed cleanup.
# It must be removed descriptor-relative, its absence synced, and remaining
# index/metacopy/redirect state normalized before the versioned marker appears.
for legacy_payload in empty old; do
    root="$T/legacy-$legacy_payload"
    new_fixture "$root"
    if [ "$legacy_payload" = empty ]; then
        : > "$root/.podroid/normalized"
    else
        printf 'normalized\n' > "$root/.podroid/normalized"
    fi
    PODROID_NORMALIZE_NS=user.overlay. "$BIN" "$root"
    assert_normalized "$root"
done

# Failure to remove or durably sync an untrusted marker cannot reach cleanup or
# publication. A later retry still treats any surviving old payload as invalid.
for operation in unlink legacy-sync; do
    root="$T/legacy-fail-$operation"
    new_fixture "$root"
    : > "$root/.podroid/normalized"
    if PODROID_NORMALIZE_NS=user.overlay. PODROID_NORMALIZE_FAIL="$operation" \
        "$BIN" "$root" >/dev/null 2>&1; then
        echo "FAIL: injected legacy marker $operation failure was ignored" >&2
        exit 1
    fi
    [ -e "$root/upper/metacopyfile" ] || {
        echo "FAIL: cleanup started before legacy marker absence was durable" >&2
        exit 1
    }
    PODROID_NORMALIZE_NS=user.overlay. "$BIN" "$root"
    assert_normalized "$root"
done

# Publication failures before rename roll back the unpublished temporary. Both
# the removal and its required directory fsync are checked. Even if rollback
# itself fails, no valid final marker can be accepted and an idempotent retry
# completes safely.
for failures in publish-rename 'publish-prepare,rollback-unlink' 'publish-prepare,rollback-sync'; do
    case_name=$(printf '%s' "$failures" | tr , -)
    root="$T/publication-$case_name"
    new_fixture "$root"
    if PODROID_NORMALIZE_NS=user.overlay. PODROID_NORMALIZE_FAIL="$failures" \
        "$BIN" "$root" >/dev/null 2>&1; then
        echo "FAIL: injected publication/rollback failure was ignored: $failures" >&2
        exit 1
    fi
    assert_unmarked "$root"
    assert_cleanup_complete "$root"
    PODROID_NORMALIZE_NS=user.overlay. "$BIN" "$root"
    assert_normalized "$root"
done

# A publication directory fsync may report failure after rename. The exact
# marker is allowed to survive because all cleanup directories were already
# durably synced. This safe state is explicit: cleanup is complete, the payload
# is exact, and retry accepts it without rerunning cleanup.
root="$T/publication-sync-safe"
new_fixture "$root"
if PODROID_NORMALIZE_NS=user.overlay. PODROID_NORMALIZE_FAIL=publish-sync \
    "$BIN" "$root" >/dev/null 2>&1; then
    echo "FAIL: injected publication sync failure was ignored" >&2
    exit 1
fi
assert_normalized "$root"
: > "$root/upper/after-safe-publication"
setfattr -n user.overlay.metacopy -v y "$root/upper/after-safe-publication"
PODROID_NORMALIZE_NS=user.overlay. "$BIN" "$root"
[ -f "$root/upper/after-safe-publication" ] || {
    echo "FAIL: exact safe marker did not suppress a second cleanup" >&2
    exit 1
}

# Overlong namespace input must fail rather than silently truncate an xattr
# name, and leave the marker absent.
root="$T/path-length"
new_fixture "$root"
long_namespace=$(printf '%0130d' 0 | tr 0 x)
if PODROID_NORMALIZE_NS="$long_namespace" "$BIN" "$root" >/dev/null 2>&1; then
    echo "FAIL: overlong xattr namespace was accepted" >&2
    exit 1
fi
assert_unmarked "$root"

# A symlinked metadata directory must never redirect marker creation.
root="$T/hostile-directory"
outside="$T/outside-directory"
mkdir -p "$root/upper" "$root/work" "$outside"
ln -s "$outside" "$root/.podroid"
if PODROID_NORMALIZE_NS=user.overlay. "$BIN" "$root" >/dev/null 2>&1; then
    echo "FAIL: symlinked .podroid directory was accepted" >&2
    exit 1
fi
[ ! -e "$outside/normalized" ] || { echo "FAIL: marker escaped through .podroid" >&2; exit 1; }

# A symlinked marker must be rejected without changing its target.
root="$T/hostile-marker"
outside="$T/outside-marker"
mkdir -p "$root/upper" "$root/work" "$root/.podroid"
printf 'outside\n' > "$outside"
ln -s "$outside" "$root/.podroid/normalized"
if PODROID_NORMALIZE_NS=user.overlay. "$BIN" "$root" >/dev/null 2>&1; then
    echo "FAIL: symlinked normalized marker was accepted" >&2
    exit 1
fi
[ "$(cat "$outside")" = outside ] || { echo "FAIL: hostile marker target changed" >&2; exit 1; }

# A complete run publishes one exact regular marker. Once present, another run
# is a no-op; this makes successful retries idempotent.
root="$T/success"
new_fixture "$root"
PODROID_NORMALIZE_NS=user.overlay. "$BIN" "$root"
assert_normalized "$root"
marker_inode=$(ls -di "$root/.podroid/normalized" | awk '{print $1}')
: > "$root/upper/after-marker"
setfattr -n user.overlay.metacopy -v y "$root/upper/after-marker"
PODROID_NORMALIZE_NS=user.overlay. "$BIN" "$root"
[ -f "$root/upper/after-marker" ] || { echo "FAIL: completed normalization ran twice" >&2; exit 1; }
[ "$(ls -di "$root/.podroid/normalized" | awk '{print $1}')" = "$marker_inode" ] || {
    echo "FAIL: successful marker was replaced" >&2
    exit 1
}

printf 'overlay normalizer tests passed (marker payload: %s)\n' "$MARKER_PAYLOAD"
