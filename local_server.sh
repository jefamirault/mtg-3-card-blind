#!/usr/bin/env bash
# Runs the 3 Card Blind web UI in the foreground, so start it in its own terminal tab and the log
# stays visible. Builds first if the code changed since the last build.
#
#   ./local_server.sh              # http://localhost:8080
#   ./local_server.sh -p 3000      # another port
#   ./local_server.sh status       # report any server already running from this checkout
#   ./local_server.sh stop         # stop it
#   ./local_server.sh restart      # stop it, rebuild if needed, start again (keeps its port)
#
# A bare port (./local_server.sh 3000) and the PORT env var also work. Other --options go to the
# server (--entries DIR, --history DIR, --cache DIR, --no-cache, --threads N).
# Refuses to start a second copy; FORCE=1 overrides.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT="${PORT:-8080}"
PORT_SET=""
ACTION="serve"
EXTRA=()

usage() {
  cat <<'EOF'
Usage: ./local_server.sh [-p PORT] [status|stop|restart] [server options]
  (no args)      run the web UI at http://localhost:8080 in the foreground
  -p, --port N   use port N (bare "./local_server.sh N" also works)
  status         report any server already running from this checkout
  stop           stop it
  restart        stop it, rebuild if needed, and start again on the same port
  -h, --help     this help
Server options: --entries DIR  --history DIR  --cache DIR  --no-cache  --threads N
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -p|--port) PORT="${2:?-p needs a port number}"; PORT_SET=1; shift 2 ;;
    -p*)       PORT="${1#-p}"; PORT_SET=1; shift ;;
    --port=*)  PORT="${1#--port=}"; PORT_SET=1; shift ;;
    status|stop|restart) ACTION="$1"; shift ;;
    -h|--help) usage; exit 0 ;;
    --no-cache) EXTRA+=("$1"); shift ;;
    --entries|--history|--cache|--threads) EXTRA+=("$1" "${2:?$1 needs a value}"); shift 2 ;;
    *)
      if [[ "$1" =~ ^[0-9]+$ ]]; then PORT="$1"; PORT_SET=1; shift
      else echo "unknown argument: $1" >&2; usage >&2; exit 2; fi ;;
  esac
done

# Web servers running from this checkout: "<pid> <port>" lines.
instances() {
  { pgrep -af "tcb\.web\.WebServer" || true; } | while read -r pid cmd; do
    [[ "$cmd" == *"-Dtcb.forgeGuiDir=$root/forge/forge-gui"* ]] || continue
    port="$(sed -En 's/.*--port[ =]([0-9]+).*/\1/p' <<<"$cmd")"
    echo "$pid ${port:-8080}"
  done
}

stop_all() {
  local found="$1"
  while read -r pid port; do
    kill "$pid" 2>/dev/null && echo "stopped pid $pid (port $port)" \
      || echo "could not stop pid $pid (already gone?)" >&2
  done <<<"$found"
  # Wait for the JVMs to exit, so the port and the jars are free.
  while read -r pid port; do
    for _ in $(seq 50); do kill -0 "$pid" 2>/dev/null || break; sleep 0.1; done
  done <<<"$found"
}

case "$ACTION" in
  status)
    found="$(instances)"
    if [[ -n "$found" ]]; then
      while read -r pid port; do
        echo "running: http://localhost:$port/ (pid $pid)"
      done <<<"$found"
    else
      echo "not running"
    fi
    exit 0
    ;;
  stop)
    found="$(instances)"
    if [[ -z "$found" ]]; then
      echo "nothing to stop: no server is running from $root"
      exit 0
    fi
    stop_all "$found"
    exit 0
    ;;
  restart)
    found="$(instances)"
    if [[ -n "$found" ]]; then
      # Keep the old port unless a new one was given.
      [[ -n "$PORT_SET" ]] || PORT="$(head -1 <<<"$found" | cut -d' ' -f2)"
      stop_all "$found"
    fi
    ;;
esac

# serve: explain instead of piling up duplicate servers.
existing="$(instances)"
if [[ -n "$existing" && -z "${FORCE:-}" ]]; then
  while read -r pid port; do
    echo "The web UI is already running: http://localhost:$port/ (pid $pid)" >&2
  done <<<"$existing"
  echo "Run './local_server.sh restart' to restart it, './local_server.sh stop' to stop it," >&2
  echo "or FORCE=1 ./local_server.sh -p <port> for a second copy." >&2
  exit 1
fi

if ss -ltn 2>/dev/null | awk '{print $4}' | grep -q ":$PORT\$"; then
  echo "Port $PORT is already in use by another process:" >&2
  ss -ltnp 2>/dev/null | awk -v p=":$PORT\$" 'NR==1 || $4 ~ p' >&2
  echo "Pick another port: ./local_server.sh -p <port>" >&2
  exit 1
fi

# Build if it never ran, or any source, resource, POM or Forge patch changed since it last did.
# (build.sh touches the stamp; jar times don't work, since Maven leaves unchanged jars alone.)
stamp="$root/target/build.stamp"
if [[ ! -f "$stamp" || ! -f "$root/web/target/tcb-web-0.1.0-SNAPSHOT.jar" ]] \
    || [[ -n "$(find "$root"/{core,cli,web}/src/main "$root"/pom.xml "$root"/{core,cli,web}/pom.xml \
        "$root"/patches -newer "$stamp" -type f -print -quit)" ]]; then
  echo "Code changed since the last build; building..."
  # Not FORCE: if another copy of the server is running, the build must not replace its jars.
  FORCE= "$root/build.sh"
fi

echo "Starting the web UI at http://localhost:$PORT/ (Ctrl-C to stop)"
exec "$root/tcb-web" --port "$PORT" ${EXTRA[@]+"${EXTRA[@]}"}
