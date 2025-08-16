import { simpleGit } from 'simple-git'

const git = simpleGit()

/**
 * get git repo latest commit
 */
export async function getLatestCommit() {
  try {
    const log = await git.log({ maxCount: 1 })
    return log.latest
  }
  catch (error) {
    console.error('Error fetching latest commit:', error)
    return null
  }
}
