export interface KitchenState {
  selected: readonly string[]
  cooking: boolean
  dark: boolean
}

export interface KitchenOptions extends KitchenState {
  reducedMotion: boolean
  onSelect: (name: string) => void
  onCook: () => void
  onHover: (name: string | null) => void
  onUnavailable: () => void
}

export interface KitchenScene {
  update: (state: KitchenState) => void
  setReducedMotion: (reduced: boolean) => void
  dispose: () => void
}
