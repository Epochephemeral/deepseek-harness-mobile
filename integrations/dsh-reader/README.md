# Additive DSH reader endpoints

The mobile reader requires `session/readerPage` and `session/readerFollow`. A host without these endpoints reports an explicit capability error; the app does not silently download full raw tool output instead.

The adjacent patch targets DSH release `0.2.1-rc.2`, source commit `d1b9c04067`. It adds two endpoints without changing the existing 21 endpoint input/result schemas or raw `session/page` / `session/follow` behavior. Apply it to a separate checkout of that exact release, regenerate Typert host/remote schemas and build the session-controller package using that checkout's build tooling. Do not apply it blindly to a newer host or replace installed JavaScript without rebuilding schemas.

```sh
git apply --check /path/to/dsh-0.2.1-rc.2-reader.patch
git apply /path/to/dsh-0.2.1-rc.2-reader.patch
```

## Reader v1 contract

- Requests use the existing session address; page requests use a pinned `throughSeq` and optional exclusive `beforeSeq`. Follow can request `assistantStream: true`. No `view` argument is needed.
- `maxRows` defaults to 128 (maximum 512); `maxBytes` defaults to 262144 (maximum 1048576). Rows count raw sequence positions, not messages or turns. The byte target covers the records array, not projections or stream traffic. An indivisible oversized public row is preserved and flagged.
- Snapshot/page acknowledgement: `reader: {version:1, fromSeq, throughSeq, nextBeforeSeq, bytes, oversized, rawDetails:'session/page'}`. Clients validate coverage before accepting data.
- Durable sequence numbers stay contiguous. Excluded records become small `reader/skip` placeholders.
- Human input, all public assistant text, tool name/description/status, explicit artifact references and turn lifecycle survive. Tool arguments/results, reasoning, injected context and replay metadata do not enter default reading data.
- Assistant transient revision/index counters can have gaps because excluded chunks are not sent. Only the reader path permits those gaps; raw streams retain strict continuity checks.
- Current task/goal/control projections remain authoritative. Original logs are never rewritten. Reader data is not model replay input.
- Mobile reader cache uses a separate namespace from raw history. Initial display is latest-only; earlier pages require a user gesture or explicit action.

## Compatibility and verification

The deployed candidate was built against the installed rc.2 dependency graph, retaining the installed client bundle. All 21 original endpoint input/result schemas compared equal, and 37 focused host tests passed, including built-route and gateway authorization checks. New endpoints require their own action grants for custom non-owner roles; normal owner cookie connections need no new grant.

Host installation and activation are separate: a profile overlay needs a host restart. Keep a rollback receipt and preserve original profile dependencies. This repository includes portable source changes, not machine-specific overlay state, credentials, session archives or generated application bundles.

Mobile validation at delivery: 856 JVM tests (852 passed, four skipped). A focused emulator run passed 48 of 49 tests; the remaining warm-reconnect test used the wrong cache namespace and was corrected. The final Zen retry fix and test-fixture correction compiled; the final emulator rerun was deferred at the user's request. Full real-device historical paging remains a separate acceptance check.
