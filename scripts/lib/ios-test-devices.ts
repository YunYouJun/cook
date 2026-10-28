interface SimulatorDevice {
  udid: string
  name: string
  isAvailable: boolean
  deviceTypeIdentifier?: string
}

function isDeviceFamily(device: SimulatorDevice, family: 'iPhone' | 'iPad') {
  return device.deviceTypeIdentifier
    ? device.deviceTypeIdentifier.startsWith(`com.apple.CoreSimulator.SimDeviceType.${family}-`)
    : device.name.startsWith(family)
}

/** simctl availability alone does not mean the selected Xcode can test on a runtime. */
export function selectIOSTestDevices(devices: Record<string, SimulatorDevice[]>, destinations: string) {
  const compatible = destinations.split(/Destinations incompatible|Ineligible destinations/i)[0]!
  const supportedIds = new Set(compatible.split('\n').flatMap((line) => {
    if (!line.includes('platform:iOS Simulator,') || line.includes('error:'))
      return []
    const id = line.match(/\bid:([\dA-F-]{36})\b/i)?.[1]
    return id ? [id.toUpperCase()] : []
  }))
  const runtimes = Object.keys(devices).filter(runtime => runtime.includes('.iOS-')).sort((a, b) => {
    const version = (runtime: string) => runtime.split('.iOS-')[1]!.split('-').map(Number)
    const left = version(a)
    const right = version(b)
    for (let i = 0; i < Math.max(left.length, right.length); i++) {
      const difference = (right[i] ?? 0) - (left[i] ?? 0)
      if (difference)
        return difference
    }
    return 0
  })
  for (const runtime of runtimes) {
    const supported = devices[runtime]!.filter(device => device.isAvailable && supportedIds.has(device.udid.toUpperCase()))
    const iphone = supported.find(device => isDeviceFamily(device, 'iPhone'))
    const ipad = supported.find(device => device.deviceTypeIdentifier
      ? device.deviceTypeIdentifier.startsWith('com.apple.CoreSimulator.SimDeviceType.iPad-Pro-13-inch-M5-')
      : device.name === 'iPad Pro 13-inch (M5)')
    ?? supported.find(device => isDeviceFamily(device, 'iPad'))
    if (iphone && ipad)
      return { iphone: iphone.udid, ipad: ipad.udid }
  }
  throw new Error('No compatible iPhone/iPad pair for the selected Xcode. Check xcodebuild -showdestinations and installed simulator runtimes.')
}
