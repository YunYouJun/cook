import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import process from 'node:process'
import { pathToFileURL } from 'node:url'

try {
  const config = JSON.parse(await readFile(process.argv[2], 'utf8'))
  const { DefaultArtifactClient } = await import(pathToFileURL(config.modulePath).href)
  const client = new DefaultArtifactClient()
  let result
  if (config.operation === 'upload') {
    const uploaded = await client.uploadArtifact(config.name, [config.payloadPath], config.directory, { retentionDays: 90, compressionLevel: 0, skipArchive: true })
    result = { id: uploaded.id, size: uploaded.size, digest: `sha256:${uploaded.digest}` }
  }
  else if (config.operation === 'download') {
    const path = join(config.directory, 'download')
    await mkdir(path, { mode: 0o700 })
    const downloaded = await client.downloadArtifact(config.artifact.id, {
      path,
      skipDecompress: config.artifact.name !== 'cook-support-ledger',
      expectedHash: config.artifact.digest,
      findBy: { token: process.env.GH_TOKEN, workflowRunId: Number(config.artifact.workflow_run.id), repositoryOwner: 'YunYouJun', repositoryName: 'cook' },
    })
    result = { digestMismatch: downloaded.digestMismatch === true }
  }
  else {
    throw new Error('Invalid artifact operation')
  }
  await writeFile(config.resultPath, JSON.stringify(result), { mode: 0o600, flag: 'wx' })
}
catch {
  process.exitCode = 1
}
