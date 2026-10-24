#!/bin/bash
# 把 packages/data 里 App 要用的 JSON 拷进 App 的 CookData 目录（CookData.load(from:) 读取）
set -euo pipefail

DATA_DIR="${SRCROOT}/../../packages/data"
DEST="${TARGET_BUILD_DIR}/${UNLOCALIZED_RESOURCES_FOLDER_PATH}/CookData"

if [ ! -f "${DATA_DIR}/recipe-meta.json" ]; then
  echo "error: 找不到 packages/data/recipe-meta.json，先在仓库根目录运行 pnpm convert"
  exit 1
fi

rm -rf "${DEST}"
mkdir -p "${DEST}"
for file in recipe-meta.json incompatible-foods.json aliases.json food.json pantry.json query.json shelf-life.json recipe-text.json recipe-amounts.json; do
  cp "${DATA_DIR}/${file}" "${DEST}/"
done
# 菜谱多时 convert 会分片输出
cp "${DATA_DIR}"/recipe.json "${DEST}/" 2>/dev/null || true
cp "${DATA_DIR}"/recipe-chunk-*.json "${DEST}/" 2>/dev/null || true
