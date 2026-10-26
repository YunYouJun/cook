export const kitchenMotion = { selection: 0.36, flight: 0.65, stagger: 0.035, cooking: 1.2 }

export function selectionPose(progress: number, selected: boolean, bounce = 1) {
  const t = Math.min(1, Math.max(0, progress))
  const pulse = Math.sin(t * Math.PI) * (1 - t)
  return { lift: (selected ? 0.07 : 0) + pulse * 0.2 * bounce, squash: pulse * 0.12, roll: pulse * 0.12 }
}

export function flightPose(progress: number) {
  const t = Math.min(1, Math.max(0, progress))
  return { progress: t, arc: Math.sin(t * Math.PI) * 1.35, scale: 1 - t * 0.8, turn: t * Math.PI * 1.5 }
}
