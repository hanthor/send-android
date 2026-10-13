#!/usr/bin/env bash
set -euo pipefail

compose=(docker compose -f dev/matrix/compose.yaml)
password='local-dev-password'
base='http://127.0.0.1:8008'

for user in alice bob; do
  "${compose[@]}" exec -T synapse register_new_matrix_user -c /data/homeserver.yaml \
    -u "$user" -p "$password" --no-admin http://localhost:8008
done

alice_json=$(curl --fail --silent --show-error -H 'Content-Type: application/json' \
  -d "{\"type\":\"m.login.password\",\"user\":\"alice\",\"password\":\"$password\"}" \
  "$base/_matrix/client/v3/login")
alice_token=$(jq -r '.access_token' <<<"$alice_json")
curl --fail --silent --show-error -X POST -H "Authorization: Bearer $alice_token" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Unencrypted test fixture","preset":"private_chat"}' \
  "$base/_matrix/client/v3/createRoom" >/dev/null

bob_json=$(curl --fail --silent --show-error -H 'Content-Type: application/json' \
  -d "{\"type\":\"m.login.password\",\"user\":\"bob\",\"password\":\"$password\"}" \
  "$base/_matrix/client/v3/login")
bob_token=$(jq -r '.access_token' <<<"$bob_json")
bob_id=$(jq -r '.user_id' <<<"$bob_json")
curl --fail --silent --show-error -X PUT -H "Authorization: Bearer $bob_token" \
  -H 'Content-Type: application/json' -d '{"displayname":"Bob"}' \
  "$base/_matrix/client/v3/profile/$bob_id/displayname" >/dev/null

printf 'Seeded Alice and Bob, Bob display name, and an unencrypted fixture room; Send creates the encrypted demo room.\n'
