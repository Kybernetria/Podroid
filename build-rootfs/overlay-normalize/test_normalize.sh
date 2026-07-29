#!/bin/sh
# Host regression suite for fail-closed legacy-overlay normalization.
set -eu
cd "$(dirname "$0")"
command -v cc >/dev/null || { echo "FAIL: need a host C compiler" >&2; exit 1; }
command -v setfattr >/dev/null || { echo "FAIL: need attr/setfattr" >&2; exit 1; }
command -v getfattr >/dev/null || { echo "FAIL: need attr/getfattr" >&2; exit 1; }

T=$(mktemp -d)
BIN="$T/podroid-overlay-normalize"
trap 'rm -rf "$T"' EXIT HUP INT TERM
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
        echo "FAIL: failed normalization published a marker: $1" >&2
        exit 1
    }
}

assert_normalized() {
    root=$1
    [ -f "$root/upper/realfile" ] || { echo "FAIL: real file removed" >&2; exit 1; }
    [ ! -e "$root/upper/metacopyfile" ] || { echo "FAIL: metacopy file kept" >&2; exit 1; }
    [ -d "$root/upper/sub" ] || { echo "FAIL: redirected directory removed" >&2; exit 1; }
    if getfattr -n user.overlay.redirect "$root/upper/sub" >/dev/null 2>&1; then
        echo "FAIL: redirect xattr kept" >&2
        exit 1
    fi
    [ ! -e "$root/work/index" ] || { echo "FAIL: index kept" >&2; exit 1; }
    [ -f "$root/.podroid/normalized" ] && [ ! -L "$root/.podroid/normalized" ] || {
        echo "FAIL: regular normalized marker missing" >&2
        exit 1
    }
}

# Every relevant operation class is injected as a hard failure. No partial run
# may publish the marker, and retrying the same partially-normalized tree must
# complete successfully.
for operation in lstat open read xattr remove unlink rmdir path close publish-sync publish-close; do
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

# A complete run publishes one regular marker. Once present, another run is a
# no-op; this makes successful retries idempotent.
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

echo "overlay normalizer tests passed"
