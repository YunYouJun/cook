// filter prompt
export async function generateRecipeImage(foods: string[]) {
  return $fetch('/api/recipes/image/generate', {
    method: 'POST',
    body: {
      foods,
    },
  })
}

export async function generateRecipeInfo(foods: string[]) {
  console.log(foods)
  return $fetch('/api/recipes/text/generate', {
    method: 'POST',
    body: {
      foods,
    },
  })
}

export async function getRecipeImage(foods: string[]) {
  const data = await generateRecipeImage(foods)
  return `data:image/png;base64,${data.images[0]}`
}
