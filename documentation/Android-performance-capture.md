# Android performance capture

Ocarina ReTuned can collect a one-session performance summary without enabling
profiling during ordinary gameplay.

## Capture a session

1. Open **Startup diagnostics** from the launcher.
2. Select **Capture performance on next run**.
3. Play the same scene and settings you want to measure.
4. Exit the game normally so the native runtime can write its summary.
5. Reopen **Startup diagnostics** and copy the report.

The capture is opt-in and one-run. It enables the runtime's existing
`TRIAEVUM_PACING_TRACE` sampling and writes the summary to the app-private
`files/diagnostics/native-runtime-summary.json` location. The original launch
profile is not modified; a private copy is used for the captured session.
The previous summary is removed when a new capture starts.

If Android force-stops the app or the process crashes before normal runtime
shutdown, the summary may not be written. A new app process clears an unconsumed
capture request, so a failed attempt does not silently enable profiling forever.

## Reading the report

- **p50 / p95 / p99 frame intervals** describe host frame timing. p95 and p99
  help reveal stutter that an average FPS value can hide.
- **Over 60 Hz / 30 Hz budget** counts sampled intervals that missed those
  frame-time budgets.
- **Pre-backend work per measured frame** is a CPU/host-side timing scope
  reported as mean, p95 and maximum milliseconds. It is not a GPU measurement.
- **Retained slow-frame phase samples** expose the individual host/renderer
  phases for frames that the runtime selected as slow. Some phases are nested
  or overlap, so do not add them as if they were independent costs.
- These host timings do not prove that guest simulation is running at the same
  rate, nor do they directly measure GPU execution time.

Compare runs only when the scene, resolution, graphics settings, driver,
thermal state and cache state are comparable. A cold first run and a warmed
repeat should be recorded separately. For sustained-performance checks, repeat
the same scene for at least ten minutes and note whether frame intervals worsen
as the device heats up.

The summary stays in the app's private storage. It is surfaced through the
local diagnostics report; it is not uploaded automatically.
