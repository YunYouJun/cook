import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../', import.meta.url))
const operationHeadings = new Set(['操作', '操作步骤', '制作步骤', '步骤', '做法'])
const ingredientHeading = '必备原料和工具'

function fingerprintTokens(tokens) {
  let hash = 14695981039346656037n
  for (const byte of new TextEncoder().encode(tokens.join('\0')))
    hash = BigInt.asUintN(64, (hash ^ BigInt(byte)) * 1099511628211n)
  return hash.toString(16).padStart(16, '0')
}

/** Match CookCore's RecipeAmountAnnotations.fingerprint byte-for-byte. */
export function recipeTextFingerprint(source) {
  const tokens = source.blocks.flatMap((block) => {
    switch (block.type) {
      case 'heading':
      case 'paragraph':
        return [block.type, block.text]
      case 'list':
        return ['list', String(block.ordered), String(block.start ?? 1), String(block.items.length), ...block.items]
      case 'image':
        return ['image', block.src, String(block.width), String(block.height), block.alt ?? '']
      default:
        return []
    }
  })
  return fingerprintTokens(tokens)
}

function validHeadingAnchor(anchor, blocks) {
  return Number.isSafeInteger(anchor?.block)
    && anchor.block >= 0 && anchor.block < blocks.length
    && anchor.item === undefined
    && blocks[anchor.block]?.type === 'heading'
    && blocks[anchor.block].text === anchor.text
}

/**
 * Rebuild the exact step identity and source set used by RecipePresentation.
 * Amount annotations only contribute extra operation headings after their fingerprint/anchor checks.
 */
export function presentationSources(source, amountEntry) {
  const stepHeadings = new Set(operationHeadings)
  if (amountEntry?.sourceFingerprint === recipeTextFingerprint(source) && Array.isArray(amountEntry.stepSections)) {
    for (const anchor of amountEntry.stepSections) {
      if (validHeadingAnchor(anchor, source.blocks))
        stepHeadings.add(anchor.text)
    }
  }

  let section = 'other'
  let ingredientHasNotes = false
  let ingredients = []
  const steps = []
  const occurrences = new Map()
  const annotatedOccurrences = new Map()

  function appendSteps(items, identityPrefix = '') {
    const counts = identityPrefix ? annotatedOccurrences : occurrences
    for (const item of items) {
      const occurrence = (counts.get(item) ?? 0) + 1
      counts.set(item, occurrence)
      steps.push({ id: `${identityPrefix}${occurrence}|${item}`, text: item })
    }
  }

  for (const block of source.blocks) {
    if (block.type === 'heading') {
      const title = block.text.trim()
      if (title === ingredientHeading)
        section = 'ingredients'
      else if (title === '计算')
        section = 'quantities'
      else if (operationHeadings.has(title))
        section = 'steps'
      else if (stepHeadings.has(title) && title !== ingredientHeading && title !== '计算')
        section = 'annotatedSteps'
      else
        section = 'other'
      continue
    }

    if (block.type === 'paragraph') {
      const value = block.text.trim()
      if (section === 'ingredients' && value)
        ingredientHasNotes = true
      if (section === 'steps' && value)
        appendSteps([value])
      else if (section === 'annotatedSteps' && value)
        appendSteps([value], 'annotated|')
      continue
    }

    if (block.type === 'list') {
      if (section === 'ingredients')
        ingredients.push(...block.items)
      else if (section === 'steps')
        appendSteps(block.items)
      else if (section === 'annotatedSteps')
        appendSteps(block.items, 'annotated|')
      continue
    }

    if (section === 'ingredients')
      ingredientHasNotes = true
  }

  const seen = new Set()
  ingredients = ingredients
    .map(item => item.trim())
    .filter((item) => {
      if (!item || seen.has(item))
        return false
      seen.add(item)
      return true
    })

  return { ingredients, steps, ingredientHasNotes }
}

async function readJson(directory, file) {
  return JSON.parse(await readFile(path.join(directory, file), 'utf8'))
}

/** Generate the trusted managed-guidance snapshot from the same public bundle bundled in the App. */
export async function buildStepGuidanceSources(dataDirectory = path.join(root, 'packages/data')) {
  const [meta, texts, amounts] = await Promise.all([
    readJson(dataDirectory, 'recipe-meta.json'),
    readJson(dataDirectory, 'recipe-text.json'),
    readJson(dataDirectory, 'recipe-amounts.json'),
  ])
  const recipes = meta.chunked
    ? (await Promise.all([...meta.chunks].sort((a, b) => a.index - b.index).map(chunk => readJson(dataDirectory, `recipe-chunk-${chunk.index}.json`)))).flat()
    : await readJson(dataDirectory, 'recipe.json')

  if (meta.schemaVersion !== 1 || typeof meta.dataVersion !== 'string' || !/^[\w.-]{1,128}$/.test(meta.dataVersion))
    throw new Error('Recipe metadata is invalid')
  if (!texts || typeof texts.recipes !== 'object' || Array.isArray(texts.recipes))
    throw new Error('Recipe text library is invalid')
  if (amounts?.schemaVersion !== 1 || typeof amounts.recipes !== 'object' || Array.isArray(amounts.recipes))
    throw new Error('Recipe amount annotations are invalid')

  const catalog = new Map(recipes.map(recipe => [String(recipe.id), recipe]))
  const sources = {}
  for (const [id, source] of Object.entries(texts.recipes)) {
    const recipe = catalog.get(id)
    if (!recipe)
      continue
    const presentation = presentationSources(source, amounts.recipes[id])
    if (presentation.steps.length === 0)
      continue
    sources[id] = {
      sourceFingerprint: recipeTextFingerprint(source),
      name: recipe.name,
      ingredients: presentation.ingredients,
      steps: presentation.steps,
    }
  }
  if (Object.keys(sources).length === 0)
    throw new Error('No public recipe has a text step for guidance')

  return {
    schemaVersion: 1,
    policyVersion: 1,
    dataVersion: meta.dataVersion,
    recipes: sources,
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const destination = path.join(root, 'cloudfunctions/cook-ai-guidance/sources.json')
  const sources = `${JSON.stringify(await buildStepGuidanceSources(), null, 2)}\n`
  if (process.argv.includes('--check')) {
    if (await readFile(destination, 'utf8') !== sources)
      throw new Error('Step guidance sources are stale; run node scripts/generate-ai-guidance-sources.mjs')
  }
  else {
    await writeFile(destination, sources)
  }
}
