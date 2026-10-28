#!/usr/bin/env bash
# Account-free reader contract + actual SQLite/Compose regressions; the same gate locally and in CI.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
classes=(
  com.labteto.dshmobile.data.SessionHistoryPagingRecoveryTest
  com.labteto.dshmobile.data.SessionHistoryStoreTest
  com.labteto.dshmobile.data.SessionReadOwnershipTest
  com.labteto.dshmobile.data.SessionHistoryDiskTest
  com.labteto.dshmobile.ui.screens.main.ChatTranscriptContinuityTest
  com.labteto.dshmobile.ui.screens.main.ChatTranscriptDiskPagingTest
  com.labteto.dshmobile.ui.screens.main.ChatTranscriptTailTest
  com.labteto.dshmobile.ui.screens.main.ChatTranscriptJumpTest
  com.labteto.dshmobile.ui.screens.main.HistoryReadingRestoreTest
  com.labteto.dshmobile.ui.screens.main.MinimalReaderUiTest
  com.labteto.dshmobile.ui.screens.main.ToolGroupDisclosureTest
  com.labteto.dshmobile.ui.screens.main.TrajectoryJournalTest
  com.labteto.dshmobile.ui.screens.main.TrajectoryDiskPagingTest
  com.labteto.dshmobile.ui.screens.main.OrphanToolResultTest
  com.labteto.dshmobile.ui.screens.main.HoldGestureTest
)
selected="$(IFS=,; printf '%s' "${classes[*]}")"
exec "${ROOT}/gradlew" -p "${ROOT}" :core:test :mock-harness:test :app:testDebugUnitTest \
  :app:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.class=${selected}" "$@"
