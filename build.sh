#!/usr/bin/env bash
# Builds Forge (with the 3CB patches) into the local Maven repo, then builds 3 Card Blind.
# Requires Java 17+ and Maven (mise.toml pins Maven; `mise install` provides it).
#
#   ./build.sh           # build; Forge is skipped when nothing under forge/ changed
#   ./build.sh --full    # rebuild Forge too
#   ./build.sh --test    # also run the core tests (about 3 minutes)
#
# Refuses to run while ./local_server.sh, ./tcb-web or ./tcb is running from this checkout: the
# build replaces the jars under them. FORCE=1 overrides.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mvn() { if command -v mise >/dev/null; then mise exec -- mvn "$@"; else command mvn "$@"; fi; }

FULL=""
TEST=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --full) FULL=1; shift ;;
    --test) TEST=1; shift ;;
    -h|--help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1 (try --help)" >&2; exit 2 ;;
  esac
done

# Java processes running this checkout's jars (the web UI or the CLI).
running="$({ pgrep -af "tcb\.(web\.WebServer|cli\.Main)" || true; } | grep -F -- "-Dtcb.forgeGuiDir=$root/forge/forge-gui" || true)"
if [[ -n "$running" && -z "${FORCE:-}" ]]; then
  echo "3 Card Blind is running from this checkout; building would replace its jars:" >&2
  while read -r pid cmd; do
    echo "  pid $pid: $([[ "$cmd" == *WebServer* ]] && echo "web server" || echo "tcb command")" >&2
  done <<<"$running"
  echo "Stop it first (./local_server.sh stop), or FORCE=1 ./build.sh." >&2
  exit 1
fi

cd "$root"
git submodule update --init --depth 1 forge

cd "$root/forge"
for patch in "$root"/patches/*.patch; do
    if git apply --reverse --check "$patch" 2>/dev/null; then
        echo "already applied: $(basename "$patch")"
    else
        echo "applying: $(basename "$patch")"
        git apply "$patch"
    fi
done

# Forge only needs rebuilding when its commit or working tree (the patches, or edits under forge/)
# changed since the last build, or when its jars are missing from the local Maven repo.
mkdir -p "$root/target"
stamp_file="$root/target/forge-build.stamp"
stamp="$( { git rev-parse HEAD; git diff; } | sha256sum | cut -d' ' -f1)"
forge_jar="$(ls "$HOME"/.m2/repository/forge/forge-gui/*/forge-gui-*.jar 2>/dev/null | head -1 || true)"
if [[ -n "$FULL" || -z "$forge_jar" || ! -f "$stamp_file" || "$(cat "$stamp_file")" != "$stamp" ]]; then
    echo "building Forge (a few minutes)..."
    # Forge must be built from its own directory (.mvn/maven.config uses a relative settings path).
    # flatten:flatten resolves ${revision} so the installed POMs are usable as dependencies.
    mvn -B -q -pl forge-gui -am flatten:flatten install -DskipTests -Dcheckstyle.skip
    echo "$stamp" > "$stamp_file"
else
    echo "Forge unchanged; skipping its build (--full to force)"
fi

cd "$root"
echo "building 3 Card Blind..."
mvn -B -q install -DskipTests
touch "$root/target/build.stamp"  # ./local_server.sh rebuilds when sources are newer than this
if [[ -n "$TEST" ]]; then
    echo "running tests..."
    mvn -B -q test -pl core
fi
echo "Built. Start the web UI with ./local_server.sh, or try: ./tcb tournament examples/decks"
