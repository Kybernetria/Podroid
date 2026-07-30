#!/bin/sh
set -eu

if [ "${PODROID_RUN_HEADSCALE_INTEGRATION:-0}" != 1 ]; then
    echo "SKIP: set PODROID_RUN_HEADSCALE_INTEGRATION=1 to run pinned Podman Headscale integration"
    exit 0
fi

HEADSCALE_IMAGE='ghcr.io/juanfont/headscale@sha256:895742a51a0661d60855359f01b2e3218afb4cca86987890dbd4fe11a4b7feeb'
TAILSCALE_IMAGE='docker.io/tailscale/tailscale@sha256:39c723bd66fbd5824298fb3be9c95f89ac3304e6051e0d9284ffcf11739d7df8'
SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/../.." && pwd)
PODMAN=${CONTAINER_ENGINE:-podman}
command -v "$PODMAN" >/dev/null || { echo "podman is required" >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }

operation="podroid-headscale-$$"
network="$operation"
headscale_container="$operation-headscale"
tailscale_container="$operation-guest"
headscale_data="$operation-headscale-data"
tailscale_state="$operation-tailscale-state"
network_octet=$((($$ % 200) + 20))
network_subnet="10.223.${network_octet}.0/24"
headscale_ip="10.223.${network_octet}.10"
temporary=$(mktemp -d)
cleanup() {
    trap - EXIT HUP INT TERM
    "$PODMAN" rm -f "$tailscale_container" "$headscale_container" >/dev/null 2>&1 || true
    "$PODMAN" volume rm -f "$tailscale_state" "$headscale_data" >/dev/null 2>&1 || true
    "$PODMAN" network rm "$network" >/dev/null 2>&1 || true
    rm -rf "$temporary"
}
trap cleanup EXIT HUP INT TERM

mkdir -p "$temporary/headscale" "$temporary/helpers"
cp "$REPO_ROOT/build-rootfs/files/usr/local/bin/podroid-tailscale-enroll" "$temporary/helpers/"
cp "$REPO_ROOT/build-rootfs/files/usr/local/bin/podroid-tailscale-status" "$temporary/helpers/"
cp "$REPO_ROOT/build-rootfs/files/usr/local/bin/podroid-tailscale-reconnect" "$temporary/helpers/"
cp "$REPO_ROOT/build-rootfs/files/usr/local/libexec/podroid-tailscale-common" "$temporary/helpers/"
chmod 0755 "$temporary/helpers/"*

openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
    -subj '/CN=Podroid Headscale Test CA' \
    -keyout "$temporary/ca.key" -out "$temporary/ca.crt" >/dev/null 2>&1
openssl req -newkey rsa:2048 -nodes -subj '/CN=headscale' \
    -keyout "$temporary/headscale/tls.key" -out "$temporary/server.csr" >/dev/null 2>&1
printf 'subjectAltName=DNS:headscale,IP:%s\n' "$headscale_ip" > "$temporary/server.ext"
openssl x509 -req -days 1 -sha256 -in "$temporary/server.csr" \
    -CA "$temporary/ca.crt" -CAkey "$temporary/ca.key" -CAcreateserial \
    -extfile "$temporary/server.ext" -out "$temporary/headscale/tls.crt" >/dev/null 2>&1
cat > "$temporary/headscale/config.yaml" <<EOF
server_url: https://${headscale_ip}:8443
listen_addr: 0.0.0.0:8443
metrics_listen_addr: 127.0.0.1:9090
grpc_listen_addr: 127.0.0.1:50443
grpc_allow_insecure: false
private_key_path: /var/lib/headscale/private.key
noise:
  private_key_path: /var/lib/headscale/noise_private.key
prefixes:
  v4: 100.64.0.0/10
  v6: fd7a:115c:a1e0::/48
derp:
  server:
    enabled: true
    region_id: 999
    region_code: podroid-test
    region_name: Podroid integration DERP
    stun_listen_addr: 0.0.0.0:3478
    private_key_path: /var/lib/headscale/derp_server_private.key
  urls: []
  paths: []
  auto_update_enabled: false
database:
  type: sqlite
  sqlite:
    path: /var/lib/headscale/db.sqlite
tls_cert_path: /etc/headscale/tls.crt
tls_key_path: /etc/headscale/tls.key
dns:
  magic_dns: true
  base_domain: podroid.test
  nameservers:
    global:
      - 1.1.1.1
EOF
chmod 0755 "$temporary" "$temporary/headscale" "$temporary/helpers"
chmod 0644 "$temporary/ca.crt" "$temporary/headscale/config.yaml" "$temporary/headscale/tls.crt"
chmod 0600 "$temporary/headscale/tls.key"

"$PODMAN" network create --subnet "$network_subnet" "$network" >/dev/null
"$PODMAN" volume create "$headscale_data" >/dev/null
"$PODMAN" volume create "$tailscale_state" >/dev/null
"$PODMAN" run -d --name "$headscale_container" --network "$network" --ip "$headscale_ip" \
    -v "$temporary/headscale:/etc/headscale:ro,Z" \
    -v "$headscale_data:/var/lib/headscale" \
    "$HEADSCALE_IMAGE" -c /etc/headscale/config.yaml serve >/dev/null

ready=0
for _ in $(seq 1 30); do
    if "$PODMAN" exec "$headscale_container" /ko-app/headscale -c /etc/headscale/config.yaml users list -o json >/dev/null 2>&1; then
        ready=1
        break
    fi
    sleep 1
done
[ "$ready" -eq 1 ] || {
    "$PODMAN" logs "$headscale_container" >&2 || true
    echo "Headscale did not become ready" >&2
    exit 1
}
user_json=$("$PODMAN" exec "$headscale_container" /ko-app/headscale \
    -c /etc/headscale/config.yaml users create podroid -o json)
user_id=$(printf '%s' "$user_json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
auth_json=$("$PODMAN" exec "$headscale_container" /ko-app/headscale \
    -c /etc/headscale/config.yaml preauthkeys create --user "$user_id" --expiration 10m -o json)
auth_key=$(printf '%s' "$auth_json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["key"])')
[ -n "$auth_key" ] || { echo "Headscale returned an empty one-use key" >&2; exit 1; }
printf '%s\n' "$auth_key" > "$temporary/one-use.key"
chmod 0600 "$temporary/one-use.key"
unset auth_key auth_json

start_guest() {
    "$PODMAN" run -d --name "$tailscale_container" --network "$network" \
        -e SSL_CERT_FILE=/podroid/ca.crt \
        -v "$temporary/ca.crt:/podroid/ca.crt:ro,Z" \
        -v "$temporary/helpers:/podroid/helpers:ro,Z" \
        -v "$tailscale_state:/var/lib/tailscale" \
        --entrypoint /bin/sh "$TAILSCALE_IMAGE" -c '
            set -eu
            mkdir -p /usr/local/libexec /run/tailscale
            cp /podroid/helpers/podroid-tailscale-common /usr/local/libexec/
            cp /podroid/helpers/podroid-tailscale-enroll /usr/local/bin/
            cp /podroid/helpers/podroid-tailscale-status /usr/local/bin/
            cp /podroid/helpers/podroid-tailscale-reconnect /usr/local/bin/
            cp /usr/local/bin/tailscale /usr/sbin/tailscaled
            ln -s /usr/sbin/tailscaled /usr/bin/tailscale
            exec /usr/local/bin/tailscaled --tun=userspace-networking \
                --state=/var/lib/tailscale/tailscaled.state \
                --socket=/run/tailscale/tailscaled.sock
        ' >/dev/null
    socket_ready=0
    for _ in $(seq 1 20); do
        if "$PODMAN" exec "$tailscale_container" test -S /run/tailscale/tailscaled.sock; then
            socket_ready=1
            break
        fi
        sleep 1
    done
    [ "$socket_ready" -eq 1 ] || return 1
}

start_guest
"$PODMAN" cp "$temporary/one-use.key" "$tailscale_container:/run/one-use.key"
"$PODMAN" exec "$tailscale_container" chmod 0600 /run/one-use.key
rm -f "$temporary/one-use.key"
if ! "$PODMAN" exec "$tailscale_container" /usr/local/bin/podroid-tailscale-enroll \
    --login-server "https://${headscale_ip}:8443" --hostname podroid-integration-guest \
    --auth-key-file /run/one-use.key; then
    "$PODMAN" logs "$tailscale_container" >&2 || true
    "$PODMAN" logs "$headscale_container" >&2 || true
    exit 1
fi
"$PODMAN" exec "$tailscale_container" test ! -e /run/one-use.key
status_json=$("$PODMAN" exec "$tailscale_container" /usr/local/bin/podroid-tailscale-status)
printf '%s' "$status_json" | python3 -c '
import json,sys
status=json.load(sys.stdin)
assert status["BackendState"] == "Running", status
'
nodes_json=$("$PODMAN" exec "$headscale_container" /ko-app/headscale \
    -c /etc/headscale/config.yaml nodes list -o json)
printf '%s' "$nodes_json" | python3 -c '
import json,sys
nodes=json.load(sys.stdin)
assert len(nodes) == 1, nodes
assert nodes[0]["name"] == "podroid-integration-guest", nodes
'

"$PODMAN" rm -f "$tailscale_container" >/dev/null
start_guest
"$PODMAN" exec "$tailscale_container" /usr/local/bin/podroid-tailscale-reconnect
reboot_status=$("$PODMAN" exec "$tailscale_container" /usr/local/bin/podroid-tailscale-status)
printf '%s' "$reboot_status" | python3 -c '
import json,sys
status=json.load(sys.stdin)
assert status["BackendState"] == "Running", status
'

echo "PASS: pinned Headscale 0.27.1 + Tailscale 1.90.9 userspace enrollment and persisted reconnect"
