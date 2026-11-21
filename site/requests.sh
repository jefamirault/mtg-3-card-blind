#!/usr/bin/env bash
#
# Collect requests from the public site, and mark them done. Settings come from site/.env
# (DEPLOY_DOMAIN and REQUESTS_TOKEN).
#
#   site/requests.sh                       # pull: list the waiting requests, and save them
#   site/requests.sh solved ID [REPLY]     # mark a request solved (REPLY is shown on the site)
#   site/requests.sh declined ID [REPLY]   # or declined
#   site/requests.sh waiting ID            # back to waiting
#
# REQUESTS_URL in the settings overrides https://$DEPLOY_DOMAIN/api/requests.
#
# pull saves every request in requests/requests.json, and the waiting decks in
# requests/waiting.txt, one per line in the import format: paste it into "Import or export
# entries" to add them (the import checks the cards).
set -euo pipefail
site="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(dirname "$site")"
env="${TCB_SITE_ENV:-$site/.env}"  # another settings file, e.g. for a test server
[[ -f "$env" ]] || { echo "Missing $env: copy site/env.example and fill it in." >&2; exit 1; }
set -a; source "$env"; set +a
: "${DEPLOY_DOMAIN:?DEPLOY_DOMAIN not set in site/.env}"
: "${REQUESTS_TOKEN:?REQUESTS_TOKEN not set in site/.env}"
api="${REQUESTS_URL:-https://$DEPLOY_DOMAIN/api/requests}"
auth=(-H "Authorization: Bearer $REQUESTS_TOKEN")

case "${1:-pull}" in
  pull)
    mkdir -p "$root/requests"
    curl -fsS "${auth[@]}" "$api" > "$root/requests/requests.json.tmp"
    mv "$root/requests/requests.json.tmp" "$root/requests/requests.json"
    # one entry per line: "Name, Card | Card | Card", or just the cards (the import names them)
    jq -r '[.[] | select(.status == "waiting") | .decks[]] | unique_by(.cards | map(ascii_downcase) | sort) | .[]
      | ((.name | gsub("[,|]"; "") | if . == "" then "" else . + ", " end) + (.cards | join(" | ")))' \
      "$root/requests/requests.json" > "$root/requests/waiting.txt"
    jq -r '.[] | select(.status == "waiting")
      | "\(.id)  \(.at[0:10])  \(.kind)  " + ([.decks[] | (if .name != "" then .name + ": " else "" end) + (.cards | join(", "))] | join("  vs  "))
        + (if .note != "" then "\n            note: \(.note)" else "" end)' "$root/requests/requests.json"
    echo "$(jq '[.[] | select(.status == "waiting")] | length' "$root/requests/requests.json") waiting." \
      "Their decks are in requests/waiting.txt, ready to import."
    ;;
  solved|declined|waiting)
    id="${2:?which request? (its id, from site/requests.sh)}"
    body="$(jq -nc --arg s "$1" --arg r "${3:-}" 'if $r == "" then {status: $s} else {status: $s, reply: $r} end')"
    curl -fsS "${auth[@]}" -H "Content-Type: application/json" -d "$body" "$api/$id" | jq -r '"\(.id): \(.status)"'
    ;;
  -h|--help) sed -n '3,14p' "$0" | sed 's/^# \{0,1\}//' ;;
  *) echo "unknown command: $1 (try --help)" >&2; exit 2 ;;
esac
