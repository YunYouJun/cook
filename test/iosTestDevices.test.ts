import { describe, expect, it } from 'vitest'
import { selectIOSTestDevices } from '../scripts/lib/ios-test-devices'

const phone = { name: 'iPhone 17 Pro', udid: '11111111-1111-1111-1111-111111111111', isAvailable: true }
const tablet = { name: 'iPad Pro', udid: '22222222-2222-2222-2222-222222222222', isAvailable: true }
const newerPhone = { ...phone, udid: '33333333-3333-3333-3333-333333333333' }
const newerTablet = { ...tablet, udid: '44444444-4444-4444-4444-444444444444' }
const destination = (id: string) => `{ platform:iOS Simulator, arch:arm64, id:${id}, OS:26.6, name:Simulator }`
const inventory = {
  'com.apple.CoreSimulator.SimRuntime.iOS-27-0': [newerPhone, newerTablet],
  'com.apple.CoreSimulator.SimRuntime.iOS-26-6': [phone, tablet],
}

describe('iOS CI destinations', () => {
  it('ignores simctl runtimes unavailable to the selected Xcode', () => {
    const destinations = [destination(phone.udid), destination(tablet.udid), 'Destinations incompatible with the scheme:', destination(newerPhone.udid), destination(newerTablet.udid)].join('\n')
    expect(selectIOSTestDevices(inventory, destinations)).toEqual({ iphone: phone.udid, ipad: tablet.udid })
  })

  it('picks the newest supported runtime with both phone and tablet coverage', () => {
    const destinations = [phone, tablet, newerPhone, newerTablet].map(device => destination(device.udid)).join('\n')
    expect(selectIOSTestDevices(inventory, destinations)).toEqual({ iphone: newerPhone.udid, ipad: newerTablet.udid })
  })

  it('keeps large iPad coverage when a newer runtime only has a phone', () => {
    const largeTablet = { name: 'iPad Pro 13-inch (M5)', udid: '55555555-5555-5555-5555-555555555555', isAvailable: true }
    const phoneOnly = { ...newerPhone, udid: '66666666-6666-6666-6666-666666666666' }
    const devices = {
      ...inventory,
      'com.apple.CoreSimulator.SimRuntime.iOS-27-0': [newerPhone, newerTablet, largeTablet],
      'com.apple.CoreSimulator.SimRuntime.iOS-27-1': [phoneOnly],
    }
    const destinations = [phone, tablet, newerPhone, newerTablet, largeTablet, phoneOnly].map(device => destination(device.udid)).join('\n')
    expect(selectIOSTestDevices(devices, destinations)).toEqual({ iphone: newerPhone.udid, ipad: largeTablet.udid })
  })

  it('fails instead of silently skipping iPad or using a placeholder', () => {
    expect(() => selectIOSTestDevices(inventory, `${destination(phone.udid)}\n{ platform:iOS Simulator, id:placeholder, name:Any iOS Simulator Device }`)).toThrow('No compatible iPhone/iPad pair')
  })

  it('does not select an unavailable device even if its id appears in destinations', () => {
    expect(() => selectIOSTestDevices({ 'com.apple.CoreSimulator.SimRuntime.iOS-26-6': [phone, { ...tablet, isAvailable: false }] }, [destination(phone.udid), destination(tablet.udid)].join('\n'))).toThrow('No compatible iPhone/iPad pair')
  })
})
