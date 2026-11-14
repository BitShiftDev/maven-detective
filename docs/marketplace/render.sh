#!/usr/bin/env bash
# Rendert die Marketplace-Bilder aus src/*.html nach docs/marketplace/*.png
# (1280 × 800 CSS-Pixel, 2-fache Pixeldichte → 2560 × 1600 PNG).
# Braucht Chromium oder Google Chrome im PATH.
set -euo pipefail
cd "$(dirname "$0")"
BROWSER=${BROWSER:-$(command -v chromium || command -v chromium-browser || command -v google-chrome)}
PROFILE=$(mktemp -d)
trap 'rm -rf "$PROFILE"' EXIT
for page in src/[0-9]*.html; do
  name=$(basename "$page" .html)
  "$BROWSER" --headless=new --disable-gpu --hide-scrollbars --no-first-run --user-data-dir="$PROFILE" \
    --window-size=1280,800 --force-device-scale-factor="${SCALE:-2}" --virtual-time-budget=2000 \
    --screenshot="$PWD/$name.png" "file://$PWD/$page" >/dev/null 2>&1
  echo "$name.png"
done
