# Windows implementation plan

1. Add backward-compatible win pairing and per-device managed status, tests first.
2. Windows package, pure conversation/protocol/audio policies and regression tests.
3. Main-process pairing, DPAPI storage, gateway/reconnect and managed synchronization.
4. Isolated native wake/VAD utility process and bounded Web Audio capture/playback.
5. Assistant UI/shared orb, tray, compact window, hotkey, privacy/lifecycle handling.
6. Full regression, package portable/installer, deploy compatible backend/plugin,
   inspect the actual Windows app, and document Windows commands/manual checks.

Use branch `codex/windows` from the tested Android release source. Do not merge
Android into main or overwrite VPS patches. Preserve all deployed credentials and
the user's existing personal phone connection. No automatic PC control or camera.
