export interface UserSettings {
  /**
   * 保留本地数据
   */
  keepLocalData: boolean
}

export const defaultSettings: UserSettings = {
  keepLocalData: true,
}
