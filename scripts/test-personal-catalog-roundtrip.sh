#!/bin/bash
set -euo pipefail
catalog_roundtrip_dir=$(mktemp -d)
trap 'rm -rf "$catalog_roundtrip_dir"' EXIT
pnpm exec tsx scripts/personal-catalog-roundtrip.ts write "$catalog_roundtrip_dir/web.json"
COOK_CATALOG_BRIDGE_INPUT="$catalog_roundtrip_dir/web.json" COOK_CATALOG_BRIDGE_OUTPUT="$catalog_roundtrip_dir/swift.json" \
  swift test --package-path apps/ios/CookCore --scratch-path "${COOK_SWIFT_SCRATCH_PATH:-$catalog_roundtrip_dir/build}" --filter PersonalCatalogTests
pnpm exec tsx scripts/personal-catalog-roundtrip.ts verify "$catalog_roundtrip_dir/swift.json"
