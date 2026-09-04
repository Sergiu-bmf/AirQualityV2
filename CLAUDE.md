# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A personal environmental-monitoring project: an Arduino with sensors (temperature/humidity, sound, light, flame, gas) streams readings over USB serial to a Python pipeline on a laptop, which averages and writes them to AWS DynamoDB. A Lambda function exposes that data over HTTP, and a native Android (Kotlin/Compose) app displays the latest reading plus history charts.

Data flow:
```
Arduino (arduino/sensor_data.ino) --USB serial--> Python pipeline (pipeline/sensor_pipeline.py) --boto3--> DynamoDB (SensorReadings table, eu-central-1)
        |                                                                                                       |
        +--> SD card data.csv (offline backup)                                 Lambda (lambda/lambda_function.py) --Function URL--> Android app
```
The Android app never talks to DynamoDB directly — it only calls the Lambda Function URL, so AWS credentials never live in the app.

`PROJECT_DOCUMENTATION.md` is the authoritative deep-dive on the *hardware and AWS* side: wiring/pin mapping, sensor calibration data and formulas, IAM identities, table schema, and a chronological debugging log of issues already solved (region typos, boto3 Decimal requirement, breadboard split ground rail, Lambda handler misconfiguration, etc.) — check it before re-debugging something that may already be solved there.

**Both docs drift from the code in specific ways — trust the source files over both:**
- `PROJECT_DOCUMENTATION.md` refers to `arduino/sensor_sketch.ino` and `backend/lambda_function.py`; the real paths are `arduino/sensor_data.ino` and `lambda/lambda_function.py`. Its inlined copies of the sketch and pipeline are snapshots and lag behind the working tree. Its Android section describes an app that has since been built differently — read `android-app/app/src/main/java/com/example/android_app/` instead.
- `README.md` describes the *aspirational* direction (urban-heat-island mapping, Wi-Fi-fingerprint geolocation) and lists a DynamoDB schema with `GeoHash`/`Lat`/`Lng`/ISO-8601 timestamps. None of that is implemented; the real table uses a numeric `timestamp` and has no geo attributes.

## Commands

**Python pipeline** — no build step; run directly:
```
pip install -r requirements.txt pyserial --break-system-packages   # or use a venv
python3 pipeline/sensor_pipeline.py
```
`requirements.txt` is missing `pyserial`, which `sensor_pipeline.py` needs for its `import serial` — install it explicitly as shown above. (`requests`, `geopy`, and `python-geohash` in `requirements.txt` are for the geolocation angle described in `README.md`; nothing in the current pipeline imports them.) Requires an Arduino connected at `SERIAL_PORT` (default `/dev/ttyACM0`, set at the top of `sensor_pipeline.py`) and AWS credentials configured for `eu-central-1` (`aws configure`). There is no Python test suite (`tests/` is empty).

**Android app** (`android-app/`):
```
cd android-app
./gradlew assembleDebug
./gradlew testDebugUnitTest                                              # JVM unit tests
./gradlew testDebugUnitTest --tests "com.example.android_app.ChartMathTest"   # single test class
./gradlew connectedAndroidTest                                           # needs a device/emulator
```

**Lambda** (`lambda/lambda_function.py`): deployed manually through the AWS Console/CLI, not via IaC. Runtime is Python 3.12; handler must be set to `lambda_function.handler`. Env vars: `TABLE_NAME`, `SHARED_SECRET`.

**Arduino** (`arduino/sensor_data.ino`): flashed via Arduino IDE/CLI, not part of any repo build. Needs the `DHT` and bundled `SPI`/`SD` libraries.

## Android app configuration (do this first — the app is inert without it)

`app/build.gradle.kts` reads three keys out of the gitignored `android-app/local.properties` and bakes them into `BuildConfig`:

```
sensor.api.baseUrl=https://<id>.lambda-url.eu-central-1.on.aws/   # trailing slash added automatically
sensor.api.key=<the Lambda's SHARED_SECRET>
sensor.deviceId=arduino-01                                        # must match DEVICE_ID in the pipeline
```
**These keys are not currently present in this checkout's `local.properties`** — only the Android Studio-generated `sdk.dir` is. With a blank `baseUrl`, `SensorRepository.isConfigured` is false, the ViewModel never fires a request, and `StatusScreen` renders `SetupCard` instead of data. That is the expected state, not a bug. `defaultApi()` still needs a syntactically valid URL for Retrofit's builder, so it substitutes `https://example.invalid/` when unconfigured. Changing any of these three values requires a rebuild, since they are compile-time constants.

## Architecture notes

### Serial + device

- **Serial protocol**: the Arduino prints one line per reading like `"Humidity: 48.00%  Temperature: 22.50°C , Sound Level: 412, Light: 300, Gas: 250, Flame: 0, FlameRaw: 320"`. The `Gas:` field is optional — `LINE_PATTERN` in the pipeline tolerates its absence — because the physical Arduino may still be running a pre-gas-sensor sketch even though the committed one prints it. When editing the line format on either side, keep both in sync (or preserve the optional-field tolerance).
- **Arduino also logs locally to SD** (`data.csv` on `SD_CS_PIN` 10) as a backup path independent of the serial pipeline: header row written once only if the file is absent, and the file is reopened/closed per write so data survives a sudden power loss. If `SD.begin()` fails the sketch continues without logging (`sdReady = false`). Its time column is `millis()` (time since power-on) — there's no RTC, so rows are only relatively ordered, not wall-clock stamped.

### Thresholds are hand-duplicated in three places

There is no shared source of truth. A threshold lives in all three of these and nothing enforces the match:

| Where | Constants | Role |
|---|---|---|
| `arduino/sensor_data.ino` | `TEMP_HIGH`, `HUMIDITY_HIGH`, `SOUND_HIGH`, `LIGHT_HIGH`, `GAS_HIGH` | instant on-device LED/buzzer feedback, per single reading |
| `pipeline/sensor_pipeline.py` | `TEMP_ALERT_HIGH`, `HUMIDITY_ALERT_HIGH`, `SOUND_DB_ALERT_HIGH`, `LIGHT_ALERT_HIGH`, `GAS_ALERT_HIGH` | the stored `alerts`/`status`, per averaged window |
| `.../ui/components/AlertThresholds.kt` | `TEMPERATURE_C`, `HUMIDITY_PERCENT`, `SOUND_DB`, `LIGHT_RAW`, `GAS_RAW`, `FLAME_RAW_DETECT` | reference lines drawn on the charts, display only |

Change one and the others silently desync — the LED can disagree with the stored status, or a chart's reference line can disagree with the traffic light above it. Note the Arduino compares *raw ADC* sound (`SOUND_HIGH = 550`) while the pipeline and app compare *dB* (`75.0`), so those two are related by `raw_to_db()` rather than being equal numbers.

This is distinct from the **validation-vs-alerting split**, which is intentional: `is_valid_reading()` rejects physically-impossible sensor glitches (e.g. temperature outside the DHT11's rated range) before a reading enters the average at all, while `check_alerts()` runs afterward on the window's averaged values and is about real-world thresholds.

### Pipeline

- **Collect/idle cycle**: `sensor_pipeline.py` alternates between `AVERAGING_WINDOW_SECONDS` (60) of collecting readings and `IDLE_SECONDS` (180) of draining-but-discarding serial input (not stopping reads — avoids OS serial buffer overflow). Only completed collecting windows with at least one valid reading get written, as one averaged row per window. So the table gains roughly one row every 4 minutes — ~360 rows a day, ~2500 a week, which is what sets the app's range options and its downsampling cap.
- **Averaging rules matter**: raw values are averaged first; sound dB is computed from the averaged raw value via `raw_to_db()`, not by averaging individual dB readings (dB is log-scale, so that would skew results). Flame detection is never averaged — a single `flame=1` reading anywhere in the window marks `flame_detected=True` for the whole window, since averaging would hide a brief real detection.
- **`status` is precomputed at write time**, not derived by readers: each window is stored as `"red"` (flame detected), `"yellow"` (any other alert), or `"green"` (clear), alongside the human-readable `alerts` list. The app reads `status` directly and never re-derives it from `alerts`.

### AWS hop

- **Decimal conversion happens at both ends**: DynamoDB writes require `Decimal`, not native Python `float`, so `floats_to_decimal()` recursively converts before every `put_item`; the Lambda mirrors it with `decimal_to_native()` before `json.dumps`, since `Decimal` isn't JSON-serializable. Table key schema: partition key `device_id` (String), sort key `timestamp` (Number, = window start).
- **Lambda HTTP surface** (routed on `rawPath` suffix, so the stage/prefix doesn't matter): `/latest` returns the single newest item for a device (`ScanIndexForward=False, Limit=1`), `/history` requires `start`/`end` unix-timestamp query params and returns `{"items": [...]}`. `device_id` is a query param defaulting to `arduino-01`. Anything else 404s.
- **`/history` paginates.** A single DynamoDB `query()` page caps at 1MB, which multi-day ranges exceed. Because results come back *ascending*, an unpaginated read drops the **newest** rows — the chart would look complete while missing the last few hours. `get_history` loops on `LastEvaluatedKey` up to `MAX_HISTORY_ITEMS` (3000, ~8 days) and sets `"truncated": true` in the body when it stops early; the app surfaces that as a banner.
- **Lambda auth**: Function URL auth type is `NONE`; the only protection is a shared-secret query param (`?key=...`) checked in code — not real authentication. The check is skipped entirely when `SHARED_SECRET` is unset, so a missing env var silently makes the endpoint fully public.
- **Two independent AWS identities** are involved and are easy to conflate: the IAM *user* the Python pipeline authenticates as (via `aws configure`) and the IAM *role* auto-created for the Lambda function. They have separate permissions and must be fixed independently if either gets an `AccessDeniedException`.

### Android app

Single-screen Compose app; layers are `data/` (Retrofit + kotlinx.serialization) → `ui/StatusViewModel` (StateFlow) → `ui/StatusScreen` + `ui/components/`. The screen is one `LazyColumn`: status card → status ribbon → coverage → alert history → one line chart per metric.

- **`SensorMetric` is the catalog of everything the device reports** (`ui/components/SensorMetric.kt`): label, unit, precision, colour, reference line, and the `SensorReading` accessor, in one enum. The status tiles and the charts both iterate it, so **adding a sensor to the sketch and the pipeline means adding exactly one entry here** — not touching two screens. `sound_raw` is deliberately not an entry: it is a monotonic transform of the dB series via `raw_to_db()`, so charting both draws the same curve twice.
- **`SensorReading` mirrors the pipeline's item shape field-for-field**, including its nullability: `gasRaw` is null when the sketch omits `Gas:`, and `soundDb` is null when `raw_to_db()` returned None. Trailing fields carry Kotlin defaults so rows written by an older pipeline still deserialize instead of crashing the screen; `Json` is configured with `ignoreUnknownKeys`. **Adding a field to the pipeline's `item` dict means adding it here**, with a default if old rows lack it.
- **404 from `/latest` is an empty state, not an error.** `SensorRepository.snapshot()` fetches `/latest` and `/history` concurrently and maps only that one case to `latest = null` ("no rows for this device yet"). Every other failure becomes a `SensorApiException` whose message names the thing to fix (401 → the shared secret, 404 on a route → the base URL, IOException → connectivity) — those strings are surfaced verbatim in the UI's error card, so keep them actionable.
- **`TrafficLight.UNKNOWN` is deliberate**: an unrecognized `status` string renders grey/"Unknown status" rather than falling back to green, so a schema change looks broken instead of looking healthy.
- **Refreshes cancel rather than queue.** `StatusViewModel.refresh()` cancels the in-flight job so a rapid range switch can't let a stale response land after a newer one; `CancellationException` is rethrown, not swallowed into the error state.
- **Every chart shares one time axis** — `StatusUiState.axisStart`/`axisEnd`, i.e. the range that was actually queried, not each series' own extent. A metric that only started reporting halfway through therefore lines up with the others instead of being stretched across the full width.
- **Absence is rendered, not smoothed over.** Three things exist only to stop sparse data from reading as calm data: charts break the line wherever consecutive rows are >`PipelineTiming.GAP_SECONDS` apart (`ChartMath.segments`), `StatusTimeline` paints each stored window at its position in time so outages show as blank track, and `DiagnosticsCard` reports rows-present vs. rows-expected against the pipeline's fixed cadence. `PipelineTiming.WINDOW_SECONDS` (240) hard-codes `AVERAGING_WINDOW_SECONDS + IDLE_SECONDS` from the pipeline — a fourth place a pipeline constant is mirrored by hand.
- **Long ranges are downsampled before drawing** (`ChartMath.renderSegments`, cap 220 points): a week is ~2500 rows against ~1000 px. Buckets are averaged, never spanning a gap, and every segment keeps at least its endpoints. Because averaging flattens spikes, the min/max in the stats strip is always computed on the **full** series, not the drawn one.
- **Chart geometry and summary stats live in `ChartMath`, outside the composable**, purely so they can be unit-tested on the JVM without an emulator (`ChartMathTest`, `HistoryDiagnosticsTest` — the project's only tests). It handles the degenerate cases the real data produces: a flat series (light at night, idle gas sensor) that would divide by zero, a single-sample window, and a touch landing outside the canvas. New chart math belongs there rather than inline in `SensorLineChart`.
- **Off-scale reference lines are not drawn.** `yFraction` clamps, so a threshold far above the data would pin to the frame edge and read as "we're at the limit"; the chart omits the line and the stats strip states the number instead.
