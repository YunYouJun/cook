import { Buffer } from 'node:buffer'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import sharp from 'sharp'

const icon = new URL('../Cook/Resources/AppIcon.icon/', import.meta.url)
const catalog = new URL('../Cook/Resources/Assets.xcassets/AppIcon.appiconset/', import.meta.url)
const read = (base, path) => readFile(new URL(path, base), 'utf8')
const composer = JSON.parse(await read(icon, 'icon.json'))
const { images } = JSON.parse(await read(catalog, 'Contents.json'))

function hexColor(color) {
  const [r, g, b, alpha] = color.replace(/^srgb:/, '').split(',').map(Number)
  if (alpha !== 1 || ![r, g, b].every(channel => channel >= 0 && channel <= 1))
    throw new Error(`Expected an opaque sRGB color, received ${color}`)
  return `#${[r, g, b].map(channel => Math.round(channel * 255).toString(16).padStart(2, '0')).join('')}`
}

function appearanceFill(item, appearance) {
  return item['fill-specializations']?.find(fill => fill.appearance === appearance)?.value
}

function backdrop(appearance) {
  // Older iOS tints this grayscale source itself. Icon Composer handles its own mono backdrop.
  if (appearance === 'tinted')
    return '<rect width="1024" height="1024" fill="#111111"/>'

  const fill = appearanceFill(composer, appearance) ?? composer.fill
  const [from, to] = fill['linear-gradient'].map(hexColor)
  const { start, stop } = fill.orientation
  return `<defs><linearGradient id="background" x1="${start.x}" y1="${start.y}" x2="${stop.x}" y2="${stop.y}">
    <stop stop-color="${from}"/><stop offset="1" stop-color="${to}"/>
  </linearGradient></defs><rect width="1024" height="1024" fill="url(#background)"/>`
}

// Icon Composer lists the frontmost group first; SVG paints the backmost layer first.
const layers = await Promise.all(composer.groups.toReversed().flatMap(group => group.layers.toReversed()).map(async layer => ({
  layer,
  svg: (await read(icon, `Assets/${layer['image-name']}`)).trim(),
})))

for (const { filename, appearances } of images) {
  const appearance = appearances?.[0]?.value
  const foreground = layers.map(({ layer, svg }) => {
    const fill = appearanceFill(layer, appearance)
    return fill ? svg.replace(/fill="#[\da-f]{6}"/gi, `fill="${hexColor(fill.solid)}"`) : svg
  }).join('\n')
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024">${backdrop(appearance)}${foreground}</svg>`
  await sharp(Buffer.from(svg)).removeAlpha().png().toFile(fileURLToPath(new URL(filename, catalog)))
}
