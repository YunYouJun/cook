#!/bin/bash
set -euo pipefail
roundtrip_dir=$(mktemp -d)
trap 'rm -rf "$roundtrip_dir"' EXIT
pnpm exec tsx scripts/private-recipe-roundtrip.ts write "$roundtrip_dir/web.json"
COOK_PRIVATE_ROUNDTRIP_INPUT="$roundtrip_dir/web.json" COOK_PRIVATE_ROUNDTRIP_OUTPUT="$roundtrip_dir/swift.json" \
  swift test --package-path apps/ios/CookCore --filter PrivateRecipeTests
pnpm exec tsx scripts/private-recipe-roundtrip.ts verify "$roundtrip_dir/swift.json"
