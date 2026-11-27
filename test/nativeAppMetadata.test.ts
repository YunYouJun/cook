import { Buffer } from 'node:buffer'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

const root = resolve(import.meta.dirname, '..')

function readPngMetadata(path: string) {
  const data = readFileSync(resolve(root, path))

  expect(data.subarray(0, 8)).toEqual(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))

  return {
    colorType: data[25],
    height: data.readUInt32BE(20),
    width: data.readUInt32BE(16),
  }
}

describe('native app metadata', () => {
  it('uses 云食册 and the native bundle id on iOS', () => {
    const iosProject = readFileSync(resolve(root, 'apps/ios/project.yml'), 'utf8')
    expect(iosProject).toContain('CFBundleDisplayName: 云食册')
    expect(iosProject).toContain('PRODUCT_BUNDLE_IDENTIFIER: fun.yunle.cook')
  })

  it('declares the adaptive Android launcher icon', () => {
    const android = 'apps/android/app/src/main'
    const manifest = readFileSync(resolve(root, android, 'AndroidManifest.xml'), 'utf8')
    const icon = readFileSync(resolve(root, android, 'res/mipmap-anydpi-v26/cook_launcher.xml'), 'utf8')
    const foreground = readFileSync(resolve(root, android, 'res/drawable/cook_launcher_foreground.xml'), 'utf8')

    expect(manifest).toContain('android:icon="@mipmap/cook_launcher"')
    expect(manifest).toContain('android:roundIcon="@mipmap/cook_launcher"')
    expect(icon).toContain('<adaptive-icon ')
    expect(icon).toContain('android:drawable="@drawable/cook_launcher_foreground"')
    expect(foreground).toContain('android:viewportWidth="1254"')
    for (const color of ['#F8F3E7', '#458D4B', '#E74532', '#FAA91D'])
      expect(foreground).toContain(`android:fillColor="${color}"`)
  })

  it('ships the overhead pot artwork for Web, filing and iOS', () => {
    const appIcon = readFileSync(resolve(root, 'apps/web/public/brand/cook-app-icon.svg'), 'utf8')
    const mark = readFileSync(resolve(root, 'apps/web/public/brand/cook-mark.svg'), 'utf8')
    const iosIconSet = JSON.parse(readFileSync(
      resolve(root, 'apps/ios/Cook/Resources/Assets.xcassets/AppIcon.appiconset/Contents.json'),
      'utf8',
    ))

    expect(appIcon).toContain('viewBox="0 0 1254 1254"')
    expect(appIcon).toContain('<stop stop-color="#0C916B"/>')
    expect(appIcon).toContain('<rect width="1254" height="1254" fill="url(#background)"/>')
    expect(mark).not.toContain('<rect')
    expect(mark).toContain('<circle cx="627" cy="611" r="334"')
    expect(readFileSync(resolve(root, 'docs/public/brand/cook-app-icon.svg'), 'utf8')).toBe(appIcon)
    expect(readFileSync(resolve(root, 'docs/public/brand/cook-app-icon.png')))
      .toEqual(readFileSync(resolve(root, 'apps/web/public/pwa-512x512.png')))
    expect(iosIconSet.images.map((image: { appearances?: { value: string }[] }) => image.appearances?.[0]?.value ?? 'light'))
      .toEqual(['light', 'dark', 'tinted'])

    expect(readPngMetadata('apps/web/public/pwa-192x192.png')).toMatchObject({ width: 192, height: 192, colorType: 2 })
    expect(readPngMetadata('apps/web/public/pwa-512x512.png')).toMatchObject({ width: 512, height: 512, colorType: 2 })
    expect(readPngMetadata('apps/web/public/apple-touch-icon.png')).toMatchObject({ width: 180, height: 180, colorType: 2 })
    for (const icon of ['AppIcon-512@2x', 'AppIcon-dark', 'AppIcon-tinted'])
      expect(readPngMetadata(`apps/ios/Cook/Resources/Assets.xcassets/AppIcon.appiconset/${icon}.png`)).toMatchObject({ width: 1024, height: 1024, colorType: 2 })
  })
})
