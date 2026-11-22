#!/usr/bin/env bash
#
# Publish the public site: export the saved tournaments (history/) as static files into
# site/public/, then rsync them to the nginx server. Settings come from site/.env (see
# site/env.example). Build first with ./build.sh: the export runs from the built jars.
#
# Usage:
#   site/deploy.sh                 # export, then deploy
#   site/deploy.sh --dry-run       # export, then show what would change; transfer nothing
#   site/deploy.sh --no-export     # deploy site/public/ as it is
#   site/deploy.sh --requests      # also install or update the request service, and restart it
#
# Export options go to the exporter after --: site/deploy.sh -- --trees --only 20261121-170736
set -euo pipefail
site="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(dirname "$site")"

DRY=""; EXPORT=1; REQUESTS=""; EXPORT_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    -n|--dry-run) DRY=1; shift ;;
    --no-export) EXPORT=""; shift ;;
    --requests) REQUESTS=1; shift ;;
    --) shift; EXPORT_ARGS=("$@"); break ;;
    -h|--help) sed -n '3,15p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1 (try --help)" >&2; exit 2 ;;
  esac
done

[[ -f "$site/.env" ]] || { echo "Missing site/.env: copy site/env.example and fill it in." >&2; exit 1; }
set -a; source "$site/.env"; set +a
: "${DEPLOY_USER:?DEPLOY_USER not set in site/.env}"
: "${DEPLOY_HOST:?DEPLOY_HOST not set in site/.env}"
: "${DEPLOY_PATH:?DEPLOY_PATH not set in site/.env}"
: "${SITE_URL:?SITE_URL not set in site/.env}"
target="$DEPLOY_USER@$DEPLOY_HOST"

if [[ -n "$EXPORT" ]]; then
  jar="$root/web/target/tcb-web-0.1.0-SNAPSHOT.jar"
  [[ -f "$jar" ]] || { echo "$jar not found; run ./build.sh first" >&2; exit 1; }
  echo "Exporting history/ to site/public/..."
  log="$root/target/site-export.log"
  mkdir -p "$root/target"
  if ! (cd "$root" && java -Xss512m -XX:+DisableExplicitGC -Xmx${TCB_HEAP:-8g} -Dtcb.forgeGuiDir="$root/forge/forge-gui" \
      -cp "$jar:$root/web/target/lib/*" tcb.web.SiteExport --history history --out site/public ${EXPORT_ARGS[@]+"${EXPORT_ARGS[@]}"}) >"$log" 2>&1; then
    tail -30 "$log" >&2
    echo "The export failed; the full log is $log" >&2
    exit 1
  fi
  grep -E '^Exported|^  [0-9-]+: replayed' "$log" | tail -5
fi
[[ -f "$site/public/index.html" ]] || { echo "site/public/ is empty: run without --no-export" >&2; exit 1; }

# Deploy stamp (see the launch skill): what's live, readable at /deploy.json.
if git -C "$root" rev-parse HEAD >/dev/null 2>&1; then
  DIRTY=false; git -C "$root" diff --quiet HEAD -- ':!forge' 2>/dev/null || DIRTY=true
  printf '{"deployed_at":"%s","commit":"%s","branch":"%s","dirty":%s}\n' \
    "$(date -u +%FT%TZ)" "$(git -C "$root" rev-parse --short HEAD)" \
    "$(git -C "$root" rev-parse --abbrev-ref HEAD)" "$DIRTY" > "$site/public/deploy.json"
fi

RSYNC_OPTS=(-az --delete --no-owner --no-group --itemize-changes)
if [[ -n "$DRY" ]]; then
  RSYNC_OPTS+=(--dry-run)
  echo "── dry run: no files will be transferred ──"
fi

if [[ -n "$REQUESTS" ]]; then
  : "${REQUESTS_DIR:?REQUESTS_DIR not set in site/.env}"
  : "${REQUESTS_PORT:?REQUESTS_PORT not set in site/.env}"
  [[ ${#REQUESTS_TOKEN} -ge 20 ]] || { echo "Set REQUESTS_TOKEN in site/.env (openssl rand -hex 24)." >&2; exit 1; }
  echo "Request service → $target:$REQUESTS_DIR"
  rsync "${RSYNC_OPTS[@]}" "$site/requests/server.js" "$target:$REQUESTS_DIR/"
  if [[ -z "$DRY" ]]; then
    # Its settings, and a systemd user unit (the deploy user has lingering on, so it runs after logout).
    # shellcheck disable=SC2087  # expanded here on purpose
    ssh "$target" bash -s <<REMOTE
set -euo pipefail
node="\$(command -v node)" || { echo "node isn't installed on the server" >&2; exit 1; }
umask 077
mkdir -p "$REQUESTS_DIR/data" ~/.config/systemd/user
printf 'PORT=%s\nTOKEN=%s\nDATA_DIR=%s\nCARDS_FILE=%s\n' "$REQUESTS_PORT" "$REQUESTS_TOKEN" "$REQUESTS_DIR/data" "$DEPLOY_PATH/data/cards.json" > "$REQUESTS_DIR/env"
cat > ~/.config/systemd/user/3cb-requests.service <<UNIT
[Unit]
Description=3 Card Blind request queue
After=network.target

[Service]
EnvironmentFile=$REQUESTS_DIR/env
ExecStart=\$node $REQUESTS_DIR/server.js
Restart=on-failure
MemoryMax=100M

[Install]
WantedBy=default.target
UNIT
systemctl --user daemon-reload
systemctl --user enable --now 3cb-requests >/dev/null 2>&1 || true
systemctl --user restart 3cb-requests
sleep 1
systemctl --user is-active 3cb-requests
REMOTE
  fi
fi

echo "Site → $target:$DEPLOY_PATH/ ($SITE_URL/)"
rsync "${RSYNC_OPTS[@]}" "$site/public/" "$target:$DEPLOY_PATH/" | grep -v -E '^\.[fd] |/$' || true
echo "Done. → $SITE_URL/"
