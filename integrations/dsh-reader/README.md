# Reader host dependency

Server implementation, source patches, generated package patches, tests and build provenance are maintained only in the personal [DSH Desktop fork](https://github.com/Epochephemeral/dsh-desktop/tree/master/runtime-customizations/session-reader). That repository is a GitHub fork of [anywhere-labs/dsh-desktop](https://github.com/anywhere-labs/dsh-desktop) and owns the source/package patches for its existing Yarn patch mechanism. Default Desktop packaging integration is still pending; the existing installation continues using its verified profile overlay. The official framework pin remains unchanged; a separate personal DSH fork is not required.

This mobile repository owns only the client and its protocol/UX tests. Do not add another server implementation patch here.

## Required contract

- Main conversations call `session/readerFollow` and `session/readerPage` and require reader-v1 coverage acknowledgement before publishing data.
- Reader events retain human input, public assistant text, lightweight tool status, explicit artifacts and turn lifecycle. Raw tool arguments/results, reasoning and injected context are not part of default reading data.
- The initial page is latest-only. Earlier pages load on a user gesture or explicit navigation/action. Reader cache is isolated from raw history.
- Existing raw endpoints, original logs and current task/goal projections remain unchanged. Explicit subagent detail history still uses its existing raw path.
- An unsupported host reports a capability error; there is no silent raw-data fallback.

## Installation boundary

Committing or building Desktop source does not update an already installed app. Use a Desktop build containing the reader customization or the previously installed, version-matched profile overlay. Keep a working overlay until a replacement Desktop package has been installed and verified. The Desktop repository owns compatibility and build instructions; the mobile client does not manage host package deployment.
