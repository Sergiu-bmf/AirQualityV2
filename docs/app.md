# Android app

Native Kotlin with Jetpack Compose, built on a Nothing Phone 2 over USB debugging rather than
an emulator. Single screen, no navigation library.

<div class="grid cards" markdown>

-   ![The app showing a red flame status](images/app-flame-detected.png)

    Status card, ribbon, coverage and alert history

-   ![The launch prompt asking for an email address](images/app-onboarding-prompt.png)

    Asked on every launch until an address is set

-   ![The alert settings sheet with an address saved](images/app-alerts-sheet-on.png)

    Settings are stored server-side, not on the phone

</div>

## Layers

```
data/       Retrofit + kotlinx.serialization
  ↓
ui/StatusViewModel        StateFlow
  ↓
ui/StatusScreen + ui/components/
```

The screen is one `LazyColumn`: status card → status ribbon → coverage → alert history → one
line chart per metric.

## Design decisions

### `SensorMetric` is the single catalog

`ui/components/SensorMetric.kt` holds label, unit, precision, colour, reference line and the
`SensorReading` accessor for every metric, in one enum. Both the status tiles and the charts
iterate it, so **adding a sensor means adding exactly one entry** rather than touching two
screens.

`sound_raw` is deliberately absent: it is a monotonic transform of the dB series, so charting
both would draw the same curve twice.

### Derived units are computed at read time

Lux, the Rs/R₀ gas ratio and the flame percentage are calculated in the app
(`SensorConversions.kt`), not stored. This departs from the `sound_db` precedent on purpose:
those calibrations are provisional, so changing a constant re-scales the **entire** history on
next launch instead of splicing a differently-scaled tail onto old rows. `sound_db` stays
precomputed because it was fitted to real measurements and isn't expected to move.

### Absence is rendered, not smoothed over

Three separate things exist to stop sparse data reading as calm data:

- Charts break the line wherever consecutive rows are more than `GAP_SECONDS` apart.
- `StatusTimeline` paints each stored window at its position in time, so outages show as
  blank track.
- `DiagnosticsCard` reports rows-present against rows-expected for the pipeline's fixed
  cadence.

### `TrafficLight.UNKNOWN` is deliberate

An unrecognised `status` string **and a missing one** both render grey rather than falling
back to green, so a schema change looks broken instead of looking healthy.

!!! note "Why `status` is nullable"

    Rows written before the pipeline computed a status still exist in the table, *with flame
    alerts on them*. A `"green"` default painted those healthy, showing a green light above a
    card reading "Flame detected during this window!".

### Other behaviours worth knowing

- **404 from `/latest` is an empty state, not an error**: it means "no rows for this device
  yet". Every other failure becomes a message naming the thing to fix (401 → the shared
  secret, 404 on a route → the base URL, `IOException` → connectivity), surfaced verbatim.
- **Refreshes cancel rather than queue**, so a rapid range switch can't let a stale response
  land after a newer one.
- **Every chart shares one time axis**, the range that was actually queried, not each
  series' own extent, so a metric that started reporting halfway through lines up with the
  others.
- **Long ranges are downsampled before drawing** (cap 220 points; a week is ~2500 rows against
  ~1000 px). Buckets never span a gap, and because averaging flattens spikes the min/max in
  the stats strip is always computed on the **full** series.
- **Off-scale reference lines are not drawn.** A threshold far above the data would pin to the
  frame edge and read as "we're at the limit"; the chart omits the line and states the number
  instead.
- **No auto-refresh.** `refresh()` fires on launch, on range change, and on pull-to-refresh.
  There is no polling timer, not even on resume.

## Notification settings

The Alerts sheet edits settings that live **server-side**, because they are read by the
pipeline on the laptop; nothing here makes the phone itself listen for anything.

The launch prompt appears whenever no email address is set, on every launch. Declining closes
it for the session and is recorded server-side as an empty channel list, so the pipeline reads
it as a deliberate silence, but it returns next launch. An unconfigured fire alarm should
keep saying so rather than being permanently dismissed by one tap.

No Android notification permission is involved: the alert is an email, so there is no system
notification to grant on Android 13+.

## Configuration

Three keys in the gitignored `android-app/local.properties`, baked into `BuildConfig`:

```properties
sensor.api.baseUrl=https://<id>.lambda-url.eu-central-1.on.aws/
sensor.api.key=<the Lambda's SHARED_SECRET>
sensor.deviceId=arduino-01
```

They are compile-time constants, so changing any of them requires a rebuild. With a blank
`baseUrl` the app renders a setup card instead of data. That is the expected unconfigured
state, not a bug.

## Tests

`ChartMathTest`, `HistoryDiagnosticsTest`, `SensorConversionsTest` and `NotificationPrefsTest`
run on the JVM without an emulator, which is why chart geometry and summary statistics live in
`ChartMath` outside the composable. They cover the degenerate cases real data produces: a flat
series that would divide by zero, a single-sample window, and a touch landing outside the
canvas.

```bash
cd android-app && ./gradlew testDebugUnitTest
```
