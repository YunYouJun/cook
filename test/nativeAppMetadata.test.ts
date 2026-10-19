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
  it('uses 云食册 on iOS and 食用手册 on Android', () => {
    const capacitorConfig = readFileSync(resolve(root, 'capacitor.config.ts'), 'utf8')
    const iosInfo = readFileSync(resolve(root, 'ios/App/App/Info.plist'), 'utf8')
    const androidStrings = readFileSync(
      resolve(root, 'android/app/src/main/res/values/strings.xml'),
      'utf8',
    )

    expect(capacitorConfig).toContain('appName: \'食用手册\'')
    expect(iosInfo).toMatch(/<key>CFBundleDisplayName<\/key>\s*<string>云食册<\/string>/)
    expect(androidStrings).toContain('<string name="app_name">食用手册</string>')
    expect(androidStrings).toContain('<string name="title_activity_main">食用手册</string>')
  })

  it('ships the selected Cook icon across web, iOS, and Android', () => {
    const appIcon = readFileSync(resolve(root, 'apps/web/public/brand/cook-app-icon.svg'), 'utf8')
    const mark = readFileSync(resolve(root, 'apps/web/public/brand/cook-mark.svg'), 'utf8')
    const androidBackground = readFileSync(
      resolve(root, 'android/app/src/main/res/drawable/ic_launcher_gradient.xml'),
      'utf8',
    )
    const androidAdaptiveIcon = readFileSync(
      resolve(root, 'android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml'),
      'utf8',
    )
    const iosIconSet = JSON.parse(readFileSync(
      resolve(root, 'ios/App/App/Assets.xcassets/AppIcon.appiconset/Contents.json'),
      'utf8',
    ))

    // 晴空蓝渐变底板 + 白色锅与云 + 明黄长勺，见 docs/decisions/0005-app-icon.md
    expect(appIcon).toContain('viewBox="0 0 64 64"')
    expect(appIcon).toContain('<stop stop-color="#55AAFF"/>')
    expect(appIcon).toContain('<stop offset="1" stop-color="#2563EB"/>')
    expect(appIcon).toContain('<rect width="64" height="64" fill="url(#cook-sky)"/>')
    expect(appIcon).not.toContain('rx="14"')
    expect(mark).not.toContain('<rect width="64" height="64"')
    expect(mark).toContain('#FACC15')
    expect(androidBackground).toContain('android:startColor="#55AAFF"')
    expect(androidBackground).toContain('android:endColor="#2563EB"')
    expect(androidAdaptiveIcon).toContain('@drawable/ic_launcher_gradient')
    expect(iosIconSet.images.map((image: { appearances?: { value: string }[] }) => image.appearances?.[0]?.value ?? 'light'))
      .toEqual(['light', 'dark', 'tinted'])

    expect(readPngMetadata('apps/web/public/pwa-192x192.png')).toMatchObject({ width: 192, height: 192, colorType: 2 })
    expect(readPngMetadata('apps/web/public/pwa-512x512.png')).toMatchObject({ width: 512, height: 512, colorType: 2 })
    expect(readPngMetadata('apps/web/public/apple-touch-icon.png')).toMatchObject({ width: 180, height: 180, colorType: 2 })
    for (const icon of ['AppIcon-512@2x', 'AppIcon-dark', 'AppIcon-tinted'])
      expect(readPngMetadata(`ios/App/App/Assets.xcassets/AppIcon.appiconset/${icon}.png`)).toMatchObject({ width: 1024, height: 1024, colorType: 2 })
    expect(readPngMetadata('android/app/src/main/res/mipmap-mdpi/ic_launcher_foreground.png')).toMatchObject({ width: 108, height: 108, colorType: 6 })
  })
})
