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
  it('uses 食用手册 as the installed app name on iOS and Android', () => {
    const capacitorConfig = readFileSync(resolve(root, 'capacitor.config.ts'), 'utf8')
    const iosInfo = readFileSync(resolve(root, 'ios/App/App/Info.plist'), 'utf8')
    const androidStrings = readFileSync(
      resolve(root, 'android/app/src/main/res/values/strings.xml'),
      'utf8',
    )

    expect(capacitorConfig).toContain('appName: \'食用手册\'')
    expect(iosInfo).toContain('<string>食用手册</string>')
    expect(androidStrings).toContain('<string name="app_name">食用手册</string>')
    expect(androidStrings).toContain('<string name="title_activity_main">食用手册</string>')
  })

  it('ships the selected Cook icon across web, iOS, and Android', () => {
    const appIcon = readFileSync(resolve(root, 'public/brand/cook-app-icon.svg'), 'utf8')
    const mark = readFileSync(resolve(root, 'public/brand/cook-mark.svg'), 'utf8')
    const androidBackground = readFileSync(
      resolve(root, 'android/app/src/main/res/values/ic_launcher_background.xml'),
      'utf8',
    )

    expect(appIcon).toContain('viewBox="0 0 1254 1254"')
    expect(appIcon).toContain('<path fill="#07855F" d="M0 0h1254v1254H0z"/>')
    expect(appIcon).toContain('<g transform="translate(0 16)">')
    expect(mark).not.toContain('M0 0h1254v1254H0z')
    expect(mark).toContain('<g transform="translate(0 16)">')
    expect(androidBackground).toContain('#07855F')

    expect(readPngMetadata('public/pwa-192x192.png')).toMatchObject({ width: 192, height: 192, colorType: 2 })
    expect(readPngMetadata('public/pwa-512x512.png')).toMatchObject({ width: 512, height: 512, colorType: 2 })
    expect(readPngMetadata('public/apple-touch-icon.png')).toMatchObject({ width: 180, height: 180, colorType: 2 })
    expect(readPngMetadata('ios/App/App/Assets.xcassets/AppIcon.appiconset/AppIcon-512@2x.png')).toMatchObject({ width: 1024, height: 1024, colorType: 2 })
    expect(readPngMetadata('android/app/src/main/res/mipmap-mdpi/ic_launcher_foreground.png')).toMatchObject({ width: 108, height: 108, colorType: 6 })
  })
})
