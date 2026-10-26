import * as THREE from 'three'
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js'
import { ingredientCatalog } from './ingredient-catalog'

/** Shared authored geometry: kitchen, documentation viewer and native USD export. */
export function createIngredientLibrary() {
  const geometries = new Set<THREE.BufferGeometry>()
  const materials = new Set<THREE.Material>()
  function material(color: string, roughness = 0.72) {
    const result = new THREE.MeshStandardMaterial({ color, roughness, flatShading: true })
    materials.add(result)
    return result
  }
  const palette = {
    tomato: material('#e75b42', 0.48),
    tomatoLight: material('#f07950'),
    leaf: material('#4c893d'),
    leafLight: material('#83af4e'),
    egg: material('#f5e4c7'),
    eggShade: material('#e4c9a1'),
    potato: material('#d7ab67'),
    eye: material('#a77d46'),
    carrot: material('#f28a31'),
    carrotShade: material('#df722a'),
    cucumber: material('#3f803f'),
    cucumberLight: material('#76a44c'),
    tofu: material('#fff3ce'),
    pore: material('#e0cda5'),
    chicken: material('#e6a48d'),
    bone: material('#fff4dd'),
    rice: material('#fffae8'),
    blue: material('#407d9f', 0.4),
    gold: material('#ddb778'),
  }
  function mesh(parent: THREE.Object3D, geometry: THREE.BufferGeometry, mat: THREE.Material, x = 0, y = 0, z = 0) {
    geometries.add(geometry)
    const result = new THREE.Mesh(geometry, mat)
    result.position.set(x, y, z)
    result.castShadow = true
    result.receiveShadow = true
    parent.add(result)
    return result
  }
  function oval(parent: THREE.Object3D, mat: THREE.Material, position: [number, number, number], size: [number, number, number], segments = 12) {
    const result = mesh(parent, new THREE.SphereGeometry(1, segments, 8), mat, ...position)
    result.scale.set(...size)
    return result
  }
  function leaf(parent: THREE.Object3D, angle: number, length: number, y: number) {
    const shape = new THREE.Shape()
    shape.moveTo(0, 0)
    shape.quadraticCurveTo(-0.07, length * 0.48, 0, length)
    shape.quadraticCurveTo(0.075, length * 0.4, 0, 0)
    const result = mesh(parent, new THREE.ExtrudeGeometry(shape, { depth: 0.012, bevelEnabled: false, curveSegments: 2 }), palette.leaf, 0, y, 0)
    result.rotation.set(-Math.PI / 2 + 0.24, 0, angle)
    return result
  }
  function create(name: string) {
    const group = new THREE.Group()
    const item = ingredientCatalog.find(item => item.name === name)
    if (!item)
      throw new Error(`Unknown ingredient model: ${name}`)
    group.name = `Ingredient_${item.id}`
    if (item.id === 'tomato') {
      const geometry = new THREE.SphereGeometry(0.205, 20, 12)
      const positions = geometry.getAttribute('position')
      for (let i = 0; i < positions.count; i++) {
        const x = positions.getX(i)
        const y = positions.getY(i)
        const z = positions.getZ(i)
        const lobes = 1 + 0.055 * Math.cos(Math.atan2(z, x) * 5)
        positions.setXYZ(i, x * lobes, y * 0.82, z * lobes)
      }
      geometry.computeVertexNormals()
      mesh(group, geometry, palette.tomato, 0, 0.18, 0)
      for (let i = 0; i < 5; i++) leaf(group, i * Math.PI * 2 / 5, 0.15, 0.34)
      const stem = mesh(group, new THREE.CylinderGeometry(0.015, 0.023, 0.09, 6), palette.leaf, 0, 0.375, 0)
      stem.rotation.z = -0.24
    }
    else if (item.id === 'egg') {
      for (const [x, y, z, scale, tilt] of [[-0.08, 0.18, -0.02, 1, -0.16], [0.15, 0.12, 0.08, 0.82, 1.1]]) {
        const geometry = new THREE.SphereGeometry(1, 16, 12)
        const p = geometry.getAttribute('position')
        for (let i = 0; i < p.count; i++) {
          const taper = 1 - p.getY(i) * 0.16
          p.setXYZ(i, p.getX(i) * taper * 0.12, p.getY(i) * 0.17, p.getZ(i) * taper * 0.12)
        }
        geometry.computeVertexNormals()
        const egg = mesh(group, geometry, palette.egg, x, y, z)
        egg.scale.setScalar(scale!)
        egg.rotation.z = tilt!
      }
    }
    else if (item.id === 'potato') {
      const potato = oval(group, palette.potato, [0, 0.15, 0], [0.26, 0.15, 0.18], 16)
      const p = potato.geometry.getAttribute('position')
      for (let i = 0; i < p.count; i++) {
        const ripple = 1 + 0.06 * Math.sin(p.getX(i) * 7 + p.getZ(i) * 3)
        p.setXYZ(i, p.getX(i) * ripple, p.getY(i) * ripple, p.getZ(i) * ripple)
      }
      potato.geometry.computeVertexNormals()
      for (const [x, y, z] of [[-0.12, 0.275, 0.025], [0.09, 0.283, 0.03], [0.19, 0.2, 0.075], [-0.02, 0.23, 0.13]])
        oval(group, palette.eye, [x!, y!, z!], [0.018, 0.008, 0.012], 6)
    }
    else if (item.id === 'carrot') {
      const body = new THREE.Group()
      body.rotation.z = 1.2
      body.position.set(-0.02, 0.12, 0)
      group.add(body)
      mesh(body, new THREE.ConeGeometry(0.115, 0.5, 12), palette.carrot)
      for (let i = 0; i < 3; i++) {
        const mark = mesh(body, new THREE.TorusGeometry(0.04 + i * 0.025, 0.004, 3, 12, 1.4), palette.carrotShade, 0, 0.07 - i * 0.12, 0)
        mark.rotation.x = Math.PI / 2
      }
      for (let i = 0; i < 3; i++) {
        const green = oval(group, i % 2 ? palette.leaf : palette.leafLight, [0.28, 0.25 + i * 0.025, (i - 1) * 0.06], [0.17, 0.027, 0.047])
        green.rotation.set(0, (i - 1) * 0.6, 0.3 + i * 0.2)
      }
    }
    else if (item.id === 'cucumber') {
      const body = oval(group, palette.cucumber, [0, 0.115, 0], [0.29, 0.105, 0.105], 16)
      body.rotation.y = -0.18
      for (let i = 0; i < 3; i++) {
        const stripe = oval(group, palette.cucumberLight, [0, 0.17 + i * 0.018, -0.065 + i * 0.045], [0.255, 0.009, 0.012])
        stripe.rotation.y = -0.18
      }
      for (let i = 0; i < 7; i++)
        oval(group, palette.cucumberLight, [-0.2 + i * 0.062, 0.21, (i % 2 ? 1 : -1) * 0.035], [0.012, 0.014, 0.012], 6)
    }
    else if (item.id === 'tofu') {
      for (const [x, z, angle] of [[-0.085, 0.04, -0.12], [0.14, -0.07, 0.15]]) {
        const cube = mesh(group, new RoundedBoxGeometry(0.23, 0.22, 0.24, 1, 0.018), palette.tofu, x, 0.12, z)
        cube.rotation.y = angle!
      }
      for (const [x, z] of [[-0.12, 0.02], [-0.045, 0.08], [0.16, -0.09]])
        oval(group, palette.pore, [x!, 0.232, z!], [0.012, 0.003, 0.008], 6)
    }
    else if (item.id === 'chicken') {
      const flesh = oval(group, palette.chicken, [-0.075, 0.14, 0], [0.22, 0.135, 0.16], 16)
      flesh.rotation.y = -0.15
      const shaft = mesh(group, new THREE.CylinderGeometry(0.035, 0.055, 0.2, 8), palette.bone, 0.16, 0.13, 0)
      shaft.rotation.z = -Math.PI / 2
      oval(group, palette.bone, [0.27, 0.135, -0.035], [0.055, 0.045, 0.04])
      oval(group, palette.bone, [0.27, 0.135, 0.035], [0.055, 0.045, 0.04])
    }
    else {
      const profile = [[0.11, 0], [0.12, 0.025], [0.16, 0.06], [0.205, 0.15], [0.22, 0.22], [0.205, 0.23], [0.19, 0.15], [0.11, 0.04]].map(([x, y]) => new THREE.Vector2(x, y))
      mesh(group, new THREE.LatheGeometry(profile, 16), palette.blue, 0, 0.015, 0)
      const rim = mesh(group, new THREE.TorusGeometry(0.211, 0.012, 5, 24), palette.gold, 0, 0.24, 0)
      rim.rotation.x = Math.PI / 2
      oval(group, palette.rice, [0, 0.24, 0], [0.195, 0.08, 0.195], 16)
      for (let i = 0; i < 15; i++) {
        const r = 0.04 + (i % 3) * 0.05
        const grain = oval(group, palette.rice, [Math.sin(i * 2.4) * r, 0.318 - r * 0.22, Math.cos(i * 2.4) * r], [0.032, 0.012, 0.014], 6)
        grain.rotation.y = i * 1.2
      }
    }
    return group
  }
  return { create, palette, dispose: () => {
    geometries.forEach(item => item.dispose())
    materials.forEach(item => item.dispose())
  } }
}
