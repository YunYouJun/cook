import pantry from '../../../../packages/data/pantry.json'

// 常备调料在 packages/data/pantry.json，Web 和 iOS 共用
export const pantryOptions: string[] = pantry.options
export const defaultPantry: string[] = pantry.defaults
