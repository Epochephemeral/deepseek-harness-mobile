# Session history loading

Opening a session used to put its entire first history page on `session/follow`'s
WebSocket. A host without WebSocket message compression sends that JSON verbatim,
including tool outputs. HTTP RPC on the same host can negotiate gzip through
OkHttp's existing transparent compression support.

## Current main-conversation reader

The main conversation now opens `session/readerFollow` with a target of 128 raw
sequence positions and 256 KiB of record JSON. It immediately applies that latest
snapshot; there is no automatic HTTP history hydration. Earlier pages are requested
through `session/readerPage` only after a genuine earlier-edge gesture, an explicit
load/retry action, or an explicit turn-directory navigation. A short viewport does
not trigger automatic fill requests.

Both responses require a reader-v1 acknowledgement and validated sequence coverage.
The reader cache is isolated from raw history, and cached initial previews use the
same row/byte targets rather than expanding to a complete long turn. A single
indivisible oversized public row remains intact. These limits do not include the
projection baseline or live traffic.

Public text, lightweight tool activity, artifact references and lifecycle survive;
raw tool output, reasoning and internal context do not. Original archives and raw
host endpoints remain unchanged. Current tasks/goals come from their existing
projection channels. Missing reader endpoints fail explicitly rather than silently
falling back to raw downloads. See the [reader integration guide](../integrations/dsh-reader/README.md)
for the additive host patch and compatibility contract.

The mux continues queuing live frames independently. Session switches, cache clears
and reconnect generations fence stale reads. Reader transient counters may be sparse;
raw streams retain strict continuity checks. Explicit subagent detail history still
uses its existing raw compressed HTTP path and is not covered by the main-reader
payload reduction.

## Turn directory and direct navigation

The history picker uses the host's `turnOutline` projection when available. This is
an outline of the whole log, independent of the currently loaded transcript window.
A turn is a runtime `turn/start` boundary, not each assistant step. Picking an old
turn seeks its indexed disk window or fetches a small region near that sequence;
it does not accumulate every page between that turn and the live tail. The normal
RAM window budgets remain in force. The UI resolves the start to a visible row
before dismissing the picker; failures remain retryable instead of jumping to latest.
Older hosts without this projection retain the loaded-message history fallback.

Manual acceptance: open History in a long session, choose an early turn, then choose
a recent turn and return to latest. Check that the directory retains early entries
and the selected prompt is readable without manually scrolling through all steps.

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

## Disk history and disposable previews

The user-approved policy keeps received durable history on disk and pages a reading
window into RAM. The indexed SQLite archive and processing spools live under
`noBackupFilesDir/managed-cache`, not Android's automatically reclaimable cache
folder. There is no archive capacity limit or automatic history eviction.

Identity includes the complete endpoint, remembered host ID and session ID. Child
transcripts use a separate archive namespace containing the full parent/child/mode
address, so they cannot alias ordinary sessions or a sibling with the same ID.
Both use compressed, pinned HTTP history reads. The shared staged-page helper
streams one record at a time, batches encoded rows, and publishes them only after
RPC validation and owner/connection/request/cache-epoch checks.

Modern RAM windows have message, raw-record and serialized-byte working targets;
windowing never deletes the stored records. Reads select payload lengths before
decoding. One complete oversized record remains readable; legacy chunk-based
replies retain the complete available user turn to avoid losing a hidden prefix.
Those exceptions, final DTOs and individual growing text blocks can still require
substantial memory. This is not a constant-memory guarantee for arbitrary inputs.

Older and newer disk pages preserve stable keys and scroll offsets. Incoming
history continues to be processed while the reader is away from the tail. Jump
and send wait for the latest window before moving. Covered/history-picker and
outgoing-tab panes retain display-only snapshots until their reading window and
key/offset are restored; ingestion and current action authority are not paused.
Disk failures keep the readable window and expose manual retry instead of an
automatic retry loop.

The existing gzip previews remain in `cacheDir/session-history` for compatibility.
Their saved capacity (2 GB by default), LRU and 16 MiB uncompressed per-entry bound
apply **only to disposable previews**, not the full archive. Android may reclaim
these previews. Reopening prefers the archive, falling back to the old preview.
Cache data never supplies live projections, approval/queue authority or a protocol
resume cursor. Previously cached sessions can be opened during a warm reconnect;
this change does not add a separate cold-start offline session catalog.

Settings → Data shows total size, previews, full history, stream/reply-processing
spools, RPC spools, and in-use processing data. The existing capacity picker is
explicitly preview-only. Confirmed clearing invalidates queued writes, closes the
owned archive, and removes inactive files without a VACUUM. It does not unlink
active queues/RPC/reply journals or delete hosts, credentials or remote sessions.
The RAM view remains readable. Old pending baselines do not refill a cleared
archive; newly processed live records and explicit new history reads may populate
it again. Corrupt full-history bytes are retained on read failure until explicit
clear; unlike a disposable preview they are not silently treated as a cache miss.

JVM tests cover preview compatibility, incremental processing, ordered queues,
large-response decoding and cleanup. SQLite/Compose instrumentation covers large
histories, byte windows, both directions, clear/owner races, child identity,
reading restoration, settings confirmation and retry. The real-account
`HistoryCacheNetworkTest` remains opt-in with `historyBaseUrl` and
`historySessionId`; ordinary regression runs do not supply a real endpoint.

## Connection recovery

The composer keeps the latest message's delivery status per session: sending,
received by the host, explicitly rejected, or unconfirmed. Acceptance requires an
explicit `accepted` receipt from `session/prompt`; clearing the draft or receiving
an HTTP success alone does not confirm delivery. A lost response stays unconfirmed
because the host may already have accepted the message. This status remains while
the app process is alive, including backgrounding and navigating between sessions.
It describes message delivery; model execution is shown by the transcript.

ConnectionLoop owns the transport lifecycle. Session and subagent follows report
endpoint failures only while their mux is active and open. A carrier failure is
handled by the connection owner, which replaces the generation and reopens the
streams. Chat keeps recovery silent for its first three seconds. After that
continuous interval, it shows reconnecting status or the shared diagnosis when a
new handshake fails. Diagnostic updates do not restart the interval; recovery or
a host change cancels it. Endpoint refusals and current non-carrier
errors retain their message and retry action.

`FollowFailureTest` covers carrier classification, real mux closure, endpoint
failures and retired generations. `ReconnectNetworkTest` uses the debug app’s saved
login and the same opt-in endpoint/session arguments to reconnect three times
through the production SessionStore, checking history and its error flow.
`ReconnectNoticesUiTest` checks recovery, authentication failure and retry feedback.

A remembered endpoint is handed directly to the connection loop, which continues
retrying while the host is temporarily unavailable. Returning to the foreground
resumes pending recovery while preserving healthy connections and intentional
disconnects. Connection-loop callbacks and asynchronous restarts are bound to
their active owner, so a retired loop cannot overwrite a new connection or revive
an intentional disconnect. Each ready `clientId` identifies a new connection generation, even
when intermediate UI phases are conflated. Its initialization replaces the
previous generation’s pending initialization. WebSocket close frames are
acknowledged so the connection lifecycle can advance.

Socket integration tests cover an initially offline host returning and a
server-initiated WebSocket close. `WakeRecoveryNetworkTest` additionally checks
foreground recovery and a new generation restoring history while the previous
initialization is held behind a test gate.

## Optional personal connection defaults

To include a stable default endpoint in a locally built APK, add this optional
property to the root `local.properties` file, alongside the Android SDK setting:

```properties
dsh.defaultHostUrl=https://harness.example:3081
```

The file is ignored by Git. The build accepts only an HTTP(S) endpoint with no
credentials, query or fragment. Its address is included in the resulting APK, so
use this for stable endpoint information only. Credentials continue to use the
normal sign-in and private application stores.

On a fresh install, the endpoint seeds the remembered host and connection mode.
Saved settings and an explicitly forgotten host list take priority. The connection
form initially uses the saved endpoint for the selected mode, falling back to the
configured endpoint; it preserves edits the reader has already made. When the
property is absent, the build uses the regular app defaults.
