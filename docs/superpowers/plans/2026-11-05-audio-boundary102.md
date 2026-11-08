# code102 audio boundary corrections

Stay kaiyue; preserve unrelated edits; no commit/push/install/worktrees. No playback or root policy changes. Only two approved issues.

## Task1
1. Initialize statsWindowStartNs before first packet handling/peak collection (worker start before processing loop is appropriate); retain forced no-packet/failure flush behavior. Test actual renderer first handled packet peak timestamps lie within first window, subsequent windows unchanged; don't merely test copied helper arithmetic.
2. Integration regression root stops first, app writer flushes last events afterwards. Exercise real StartupCapture.stop/mirror queue writing and DiagnosticArchive streaming ZIP with temp files and deterministic latches, mock/fake root process boundary only (no real root required). Assert stop/download remains blocked while writer delayed; final audio journal and app-full.log contain queued tail; audio-stop-final uses final length and root cursor with correct unread bytes; exported ZIP includes exact tail and boundary metadata, original files retained. Proper cleanup singleton states/threads/sinks so no cross-test leakage. Avoid production test hooks unless strictly needed; prefer reflection consistent existing tests.
Implement source/tests only; parent version/docs/build. Run meaningful targeted tests and provide report .superpowers/sdd/2026-11-05-audio-boundary102/report.md exact changed files/commands/results/limits.

## Task2
Independent review, AGENTS full check, standalone debug code102, APK version/ZIP/signature/DEX/SHA256 verification and unique archive; preserve code101.
