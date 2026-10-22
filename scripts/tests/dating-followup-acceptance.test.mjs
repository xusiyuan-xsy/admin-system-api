// Synthetic HTTP server only. No requests reach a business backend or database.
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const script = fileURLToPath(new URL('../Test-DatingFollowupAcceptance.ps1', import.meta.url))
const secret = 'synthetic-acceptance-token'
const privateText = 'synthetic-private-history-must-not-be-printed'
let scenario
const requests = []
const server = createServer((req, res) => {
  requests.push({ method: req.method, url: req.url, auth: req.headers.authorization })
  const path = new URL(req.url, 'http://localhost').pathname.replace(/^\/dev-api/, '')
  const reply = body => {
    res.setHeader('content-type', 'application/json;charset=utf-8')
    res.end(JSON.stringify(body))
  }
  if (scenario.mode === 'redirect') {
    res.writeHead(302, { location: '/redirect-must-not-follow' })
    return res.end()
  }
  if (scenario.mode === 'html') return res.end(`<html>${privateText}</html>`)
  if (scenario.mode === 'expired') return reply({ code: 401, msg: privateText })
  const permissions = scenario.profile === 'Denied' ? [] : ['dating:match:query']
  if (['Owner', 'Supervisor'].includes(scenario.profile)) permissions.push('dating:exception:handle')
  if (scenario.profile === 'Supervisor') permissions.push('dating:service:all')
  if (scenario.mode === 'overgrant') permissions.push('dating:service:all')
  if (scenario.mode === 'wildcard') permissions.push('*:*:*')
  if (scenario.mode === 'missing-handle') permissions.splice(permissions.indexOf('dating:exception:handle'), 1)
  if (path === '/getInfo') return reply({ code: 200,
    user: { userId: scenario.mode === 'wrong-user' ? 43 : 42, userName: privateText },
    roles: scenario.mode === 'admin' ? ['admin'] : ['fixture'], permissions })
  if (scenario.profile === 'Denied') return reply({ code: 403 })
  if (path === '/dating/deduction-exceptions') return reply({
    code: 200, rows: scenario.mode === 'bad-page' ? null : [], total: 0
  })
  if (path.startsWith('/dating/deduction-tasks/11/')) {
    if (path.endsWith('/history')) return reply({ code: 200, total: 1, rows: [{
      taskId: scenario.mode === 'wrong-history' ? 99 : 11, note: privateText
    }] })
    return reply({ code: 200, data: { followup: { taskId: 11, version: 1, status: 'IN_PROGRESS' } } })
  }
  if (path.startsWith('/dating/deduction-tasks/22/')) {
    if (scenario.mode === 'leaked-scope') return reply({ code: 200, data: privateText })
    return reply({ code: 500,
      msg: scenario.mode === 'database-error' ? privateText : '无权访问该配对',
      ...(scenario.mode === 'denied-data' ? { data: privateText } : {}) })
  }
  res.statusCode = 404
  reply({ code: 404, msg: privateText })
})
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
const base = `http://127.0.0.1:${server.address().port}/`
let passed = 0
async function run(profile, mode = 'normal', extra = '') {
  scenario = { profile, mode }
  requests.length = 0
  let samples = mode === 'no-samples' ? '' : profile === 'Supervisor' ? '-AllowedTaskIds 11'
    : profile === 'Denied' ? '-ForbiddenTaskIds 22' : '-AllowedTaskIds 11 -ForbiddenTaskIds 22'
  if (mode === 'overlapping-ids') samples = '-AllowedTaskIds 11 -ForbiddenTaskIds 11'
  if (mode === 'invalid-id') samples = '-AllowedTaskIds 0'
  if (mode === 'supervisor-denied') samples = '-ForbiddenTaskIds 22'
  if (mode === 'denied-allowed') samples = '-AllowedTaskIds 11'
  const uri = mode === 'proxy' ? `${base}dev-api/` : mode === 'remote' ? 'http://example.com/' : base
  const command = `$token = ConvertTo-SecureString '${secret}' -AsPlainText -Force
& '${script.replaceAll("'", "''")}' -BaseUri '${uri}' -Profile ${profile} -ExpectedUserId 42 -AccessToken $token ${samples} ${extra}`
  const result = await new Promise((resolve, reject) => {
    const child = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', command], { windowsHide: true })
    let stdout = '', stderr = ''
    const timeout = setTimeout(() => { child.kill(); reject(new Error('Fixture process timed out')) }, 30000)
    child.stdout.on('data', data => { stdout += data })
    child.stderr.on('data', data => { stderr += data })
    child.once('error', error => { clearTimeout(timeout); reject(error) })
    child.once('close', code => { clearTimeout(timeout); resolve({ code, stdout, stderr }) })
  })
  assert.ok(!result.stdout.includes(secret) && !result.stdout.includes(privateText), mode)
  assert.ok(!result.stderr.includes(secret) && !result.stderr.includes(privateText), mode)
  assert.ok(requests.every(r => r.method === 'GET'), 'Runner must not send writes')
  assert.ok(requests.every(r => r.auth === `Bearer ${secret}`), 'Expected fixture authentication')
  return { ...result, report: result.stdout.trim() ? JSON.parse(result.stdout) : null }
}
try {
  for (const profile of ['Owner', 'Supervisor', 'ReadOnly', 'Denied']) {
    const result = await run(profile)
    assert.equal(result.code, 0, result.stderr)
    assert.equal(result.report.status, 'PASSED_READ_ONLY')
    assert.equal(result.report.writeOperations, 'NOT_EXECUTED')
    assert.equal(result.report.fullWorkflowAcceptance, false)
    passed++
  }
  const proxy = await run('Owner', 'proxy')
  assert.equal(proxy.report.status, 'PASSED_READ_ONLY')
  assert.ok(requests.every(r => r.url.startsWith('/dev-api/')))
  passed++
  const partial = await run('Owner', 'no-samples')
  assert.equal(partial.report.status, 'PARTIAL_READ_ONLY')
  assert.equal(partial.report.skipped.length, 2)
  passed++
  for (const mode of ['wrong-user', 'overgrant', 'wildcard', 'admin', 'missing-handle',
    'expired', 'html', 'redirect', 'bad-page', 'database-error', 'leaked-scope', 'denied-data', 'wrong-history']) {
    const result = await run('Owner', mode)
    assert.equal(result.code, 1, mode)
    assert.equal(result.report.status, 'FAILED', mode)
    assert.ok(result.report.failedCheck, mode)
    if (['wrong-user', 'overgrant', 'wildcard', 'admin', 'missing-handle', 'expired', 'html', 'redirect'].includes(mode))
      assert.equal(requests.length, 1, mode)
    passed++
  }
  const remote = await run('Owner', 'remote')
  assert.notEqual(remote.code, 0)
  assert.equal(requests.length, 0)
  passed++
  for (const [profile, mode] of [['Owner', 'overlapping-ids'], ['Owner', 'invalid-id'],
    ['Supervisor', 'supervisor-denied'], ['Denied', 'denied-allowed']]) {
    const result = await run(profile, mode)
    assert.notEqual(result.code, 0, mode)
    assert.equal(requests.length, 0, mode)
    passed++
  }
  console.log(`PASS: ${passed} synthetic acceptance-runner scenarios; GET only; no business backend or database accessed`)
} finally {
  server.closeAllConnections()
  await new Promise(resolve => server.close(resolve))
}
