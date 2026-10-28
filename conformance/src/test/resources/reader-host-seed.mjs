// Fixture data only. The Desktop-owned backend validates and persists it; no reader implementation
// or patch is copied here. API verified against upstream source 1f81c047fc48fa190a683e469daa699857e9afe8.
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'
import { resolve } from 'node:path'
const [resolver, root, cwd] = process.argv.slice(2)
const require = createRequire(resolve(resolver))
const load = name => import(pathToFileURL(require.resolve(name)).href)
const { Context } = await load('@deepseek-ai/cordis')
const { SESSION_FORMAT_VERSION, SessionId } = await load('@deepseek-ai/dsh-session')
const backend = await load('@deepseek-ai/dsh-session-persistence-jsonl')
const ctx = new Context()
try {
  await ctx.plugin(backend, { root, compression: 'none' })
  const handle = await ctx.sessionPersistence.create({ id: SessionId('reader-contract'),
    version: SESSION_FORMAT_VERSION, createdAt: 1000, cwd, isSeeded: false, delegationDepth: 0 })
  try {
    const rows = [
      { type: 'turn/start', data: { turn: 1 } },
      { type: 'step/start', data: { turn: 1, step: 1 } },
      { type: 'user/message', surfaceOp: 'append', data: { id: 'question', role: 'user',
        source: { kind: 'user' }, content: [{ type: 'text', text: 'READER_PUBLIC_QUESTION' }] } },
      ...Array.from({ length: 400 }, (_, i) => ({ type: 'session/title', data: { title: `PRIVATE_TITLE_${i}`, source: { kind: 'user' }, messageSeqs: [] } })),
      { type: 'assistant/message', surfaceOp: 'append', data: { turn: 1, step: 1,
        message: { id: 'answer', role: 'assistant', source: { kind: 'model', provider: 'fixture', model: 'fixture' },
          content: [{ type: 'text', text: 'READER_PUBLIC_ANSWER' }, { type: 'reasoning', text: 'REASONING_SECRET' }] },
        stream: [{ type: 'text-chunks', time0: 1001, index: 0, dt: [], texts: ['REPLAY_SECRET'] },
          { type: 'chunk', time: 1002, chunk: { type: 'finish', reason: { kind: 'stop' } } }] } },
      { type: 'step/end', data: { turn: 1, step: 1 } },
      { type: 'turn/end', data: { turn: 1, reason: { kind: 'completed' } } },
    ].map((row, seq) => ({ ...row, seq, time: 1000 + seq }))
    await handle.append(rows)
    await handle.flush()
    const read = await handle.read()
    if (read.events.length !== rows.length) throw new Error('fixture persistence lost rows')
    console.log(`reader fixture persisted ${rows.length} validated events`)
  } finally { await handle.close() }
} finally { await ctx.fiber.dispose() }
