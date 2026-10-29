// Display-only mappings; the recommendation rules remain independent of icons.
// Each class reads the same SVG asset that iOS uses through its asset catalog.
export const methodIcons: Readonly<Record<string, string>> = {
  炒菜: 'i-cook-method-stir-fry',
  汤羹: 'i-cook-method-soup',
  炖煮: 'i-cook-method-stew',
  蒸: 'i-cook-method-steam',
  炸煎: 'i-cook-method-pan-fry',
  烤: 'i-cook-method-bake',
  凉拌: 'i-cook-method-salad',
  微波: 'i-cook-method-microwave',
}

export const toolIcons: Readonly<Record<string, string>> = {
  烤箱: 'i-cook-method-bake',
  空气炸锅: 'i-cook-tool-air-fryer',
  微波炉: 'i-cook-method-microwave',
  电饭煲: 'i-cook-tool-rice-cooker',
  一口大锅: 'i-cook-method-stir-fry',
}
