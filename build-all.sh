#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

resolve_mvn() {
  if [ -n "${MVN_BIN:-}" ] && [ -f "$MVN_BIN" ]; then
    printf '%s' "$MVN_BIN"
    return 0
  fi

  if command -v mvn >/dev/null 2>&1; then
    command -v mvn
    return 0
  fi

  if [ -f "/c/Program Files/JetBrains/IntelliJ IDEA 2025.3.1/plugins/maven/lib/maven3/bin/mvn.cmd" ]; then
    printf '%s' "/c/Program Files/JetBrains/IntelliJ IDEA 2025.3.1/plugins/maven/lib/maven3/bin/mvn.cmd"
    return 0
  fi

  return 1
}

MVN=$(resolve_mvn || true)
if [ -z "$MVN" ]; then
  echo "[ERROR] Maven executable not found. Set MVN_BIN or add mvn to PATH."
  exit 1
fi

build_dir() {
  dir="$1"
  label="$2"
  echo "[*] Building $label..."
  (
    cd "$dir"
    "$MVN" clean package -DskipTests
  )
}

echo "[*] Building main plugin..."
(
  cd "$ROOT_DIR"
  "$MVN" clean package install -DskipTests
)

build_dir "$ROOT_DIR/extender" "extender"
build_dir "$ROOT_DIR/modules/profiler/profiler-extender" "module profiler-extender"

for mod in anti-bot account-protection identity-enforcement geo-filtering profiler spike-detector; do
  build_dir "$ROOT_DIR/modules/$mod" "module $mod"
done

echo "[+] All builds completed successfully."

