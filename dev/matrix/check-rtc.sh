#!/usr/bin/env bash
# Checks the disposable MatrixRTC backend end to end, the way Element Call uses it: Synapse advertises
# the LiveKit transport (MSC4143), and lk-jwt-service accepts Alice's OpenID token (checked against
# Synapse's federation API through compose.yaml's `federation` proxy) and creates the LiveKit room.
# Run after seed-demo.sh; needs the host to answer on 10.0.2.2 (DEVELOPMENT.md, "MatrixRTC").
set -euo pipefail

compose=(docker compose -f dev/matrix/compose.yaml)
base='http://127.0.0.1:8008'
service='http://10.0.2.2:8090'

fail() {
  printf 'MatrixRTC check failed: %s\n' "$1" >&2
  "${compose[@]}" logs --tail 80 federation livekit lk-jwt-service >&2 || true
  exit 1
}

for attempt in $(seq 1 30); do
  curl --fail --silent "$service/healthz" >/dev/null && break
  [ "$attempt" = 30 ] && fail "lk-jwt-service is not answering on $service"
  sleep 2
done

token=$(curl --fail --silent --show-error -H 'Content-Type: application/json' \
  -d '{"type":"m.login.password","user":"alice","password":"local-dev-password","initial_device_display_name":"MatrixRTC check"}' \
  "$base/_matrix/client/v3/login" | jq -r '.access_token')

transports=$(curl --fail --silent --show-error -H "Authorization: Bearer $token" \
  "$base/_matrix/client/unstable/org.matrix.msc4143/rtc/transports")
jq -e --arg url "$service" '.rtc_transports | any(.type == "livekit" and .livekit_service_url == $url)' \
  <<<"$transports" >/dev/null || fail "Synapse advertises $transports"

openid=$(curl --fail --silent --show-error -X POST -H "Authorization: Bearer $token" \
  -H 'Content-Type: application/json' -d '{}' \
  "$base/_matrix/client/v3/user/%40alice%3Asend.test/openid/request_token")
sfu=$(curl --silent --show-error -H 'Content-Type: application/json' \
  -d "$(jq -n --argjson openid "$openid" '{room: "!rtc-check:send.test", device_id: "RTCCHECK", openid_token: $openid}')" \
  "$service/sfu/get") || fail "no answer from $service/sfu/get"
jq -e '.jwt and .url == "ws://10.0.2.2:7880"' <<<"$sfu" >/dev/null || fail "/sfu/get answered $sfu"

curl --silent -X POST -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d '{}' \
  "$base/_matrix/client/v3/logout" >/dev/null || true
printf 'MatrixRTC backend ready: %s advertises %s, which issues LiveKit tokens for %s.\n' \
  "$base" "$service" "$(jq -r .url <<<"$sfu")"
