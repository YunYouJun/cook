/**
 * 生成随机数组
 */
export function generateRandomArray(length: number, total = 1) {
  const randomArr: number[] = []
  for (let i = 0; i < total; i++) {
    const randomIndex = Math.floor(Math.random() * length)
    if (randomArr.includes(randomIndex)) {
      i--
      continue
    }
    randomArr.push(randomIndex)
  }
  return randomArr
}
