import {
  banOutline,
  basketOutline,
  eggOutline,
  flameOutline,
  leafOutline,
  restaurantOutline,
  speedometerOutline,
} from 'ionicons/icons'
import stirFryIcon from '../../../../packages/shared/Assets.xcassets/method-stir-fry.imageset/icon.svg?raw'

/** Display-only category symbols; keep recommendation and catalog data independent. */
export const categoryIcons: Readonly<Record<string, string>> = {
  vegetable: leafOutline,
  meat: eggOutline,
  staple: restaurantOutline,
  tools: `data:image/svg+xml;utf8,${stirFryIcon.replaceAll('stroke="#000"', 'stroke="currentColor"')}`,
  method: flameOutline,
  difficulty: speedometerOutline,
  pantry: basketOutline,
  avoid: banOutline,
}
