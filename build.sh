#!/usr/bin/env bash
# Builds Forge (with the 3CB patches) into the local Maven repo, then builds 3 Card Blind.
# Requires Java 17+ and Maven (mise.toml pins Maven; `mise install` provides it).
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mvn() { if command -v mise >/dev/null; then mise exec -- mvn "$@"; else command mvn "$@"; fi; }

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

# Forge must be built from its own directory (.mvn/maven.config uses a relative settings path).
# flatten:flatten resolves ${revision} so the installed POMs are usable as dependencies.
(cd "$root/forge" && mvn -B -q -pl forge-gui -am flatten:flatten install -DskipTests -Dcheckstyle.skip)

cd "$root"
mvn -B -q install -DskipTests
echo "Built. Try: ./tcb tournament examples/decks"
