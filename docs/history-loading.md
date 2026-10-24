# Session history loading

Opening a session used to put its entire first history page on `session/follow`'s
WebSocket. A host without WebSocket message compression sends that JSON verbatim,
including tool outputs. HTTP RPC on the same host can negotiate gzip through
OkHttp's existing transparent compression support.

The client now opens the live stream with `maxMessages: 1`. If the opening snapshot
has older history, `hydrateSessionSnapshot` requests `session/page` over HTTP with
`maxMessages: 60`, no `beforeSeq`, and `throughSeq` equal to the opening cursor.
Only the snapshot's records and `hasMore` are replaced. Its header, projection
watermarks and live assistant baseline remain tied to that generation.

The mux already queues subsequent live frames independently of its reader. The
history read suspends that session's collector, then the complete snapshot is
applied before queued events are consumed. Other logical streams continue to run.
Switching sessions cancels the collector and its HTTP read. Reconnects perform a
new bootstrap at their own cursor. Main and subagent transcripts share this path.

The initial history range and older-history paging remain the same. A failed HTTP
read follows the existing stream error path; the small opening is not passed off as a complete transcript.
A host that does not offer HTTP compression can still serve the protocol, but the
transfer improvement depends on its response compression and the history content.

## Build and checks

Use JDK 17 and an Android SDK with platform 35. Gradle can install missing SDK
components when their licenses have already been accepted.

```sh
./gradlew :core:test :mock-harness:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug application is `DSH Mobile Local`, version-suffixed `-local`, with package
`com.labteto.dshmobile.debug`. It installs alongside the signed release app and
keeps independent settings and authentication.

## On-device network comparison

`HistoryBootstrapNetworkTest` is opt-in. It reads an idle real session twice: once
using a 60-message WebSocket opening, once using the small opening plus HTTP page.
It verifies the same cursor, records and `hasMore`, checks HTTP gzip was negotiated,
and reports transferred bytes and elapsed time. It sends no model prompts.

Provide `historyBaseUrl` and `historySessionId` as instrumentation arguments. Place
the launch token in `files/history-benchmark.token` inside the debug app's private
storage before running; the test consumes and deletes that file. Never put the
token in an instrumentation argument, source file or log. An absent configuration
skips the test in ordinary CI.

The optional `rememberHistoryHost=true` argument also pairs that host in the debug
app using its normal credential and host stores. It does not alter the release app.

## Cached previews

The main transcript saves its recent durable journal to a gzip file in Android
`cacheDir/session-history`. Reopening first renders that journal, labelled as
syncing, while the regular compressed bootstrap runs independently. The full
authoritative snapshot replaces the preview before queued live frames are applied.
The cache does not supply a paging cursor, projection, approval, queue or partial
assistant baseline. Cached history is not incremental protocol resumption; background
network traffic is unchanged. First visits still depend on the network.

Keys isolate the complete endpoint, remembered host ID and session ID. Opening
generations guard delayed cache reads and network responses. Cache corruption is a
miss. Files survive app restarts but Android may reclaim them; they are excluded
from Android cache-directory backups. LRU eviction caps total storage at the saved
capacity (2 GB by default), with a 16 MiB uncompressed per-entry bound. The store
saves the normal recent-history window rather than the entire paged transcript.

Settings → Data shows the actual compressed file size, a capacity picker
(500 MB, 1 GB, 2 GB, 4 GB, 8 GB), and a Clear conversation cache button.
Capacity is persisted in the existing preferences store and applied immediately;
shrinking it evicts the least recently used entries. Reads and successful writes
persist a strictly increasing last-access timestamp, retained across restarts.
Clearing removes only these local previews and invalidates writes from follows
opened before clearing. The open transcript remains readable; a new
open can cache its verified history again. Credentials and remote conversations
are unaffected.

`HistoryCacheTest` covers persistence, identity isolation, eviction, corruption,
preview boundaries and clear/write races. `HistoryCacheNetworkTest` is opt-in with
`historyBaseUrl` and `historySessionId`; it uses the debug app’s saved login to
observe an idle session through the production store, compare cold/warm content,
clear the local cache and confirm the remote history is still accessible. It
prints aggregate timings and sizes, and never submits a model prompt.

`HistoryCacheUiTest` exercises the production settings section using real local
files, including size display, clearing, empty-state disabling, light/dark themes,
doubled text size, capacity selection and preference persistence.
