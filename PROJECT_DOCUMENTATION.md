# AirQualityV2 — Project Documentation

**Last updated:** August 2, 2026
**Repo structure:** `~/Github/AirQualityV2/` with subfolders `arduino/`, `pipeline/`, `backend/`, `android-app/`

---

## 1. Project Overview

An Arduino-based environmental monitoring system that reads temperature, humidity, sound level, light level, and flame detection (gas sensor pending delivery), averages readings over time windows, stores them in AWS DynamoDB via a Python pipeline running on a Linux laptop, and will be visualized through a native Android app with push notifications for critical alerts (e.g. flame detected).

### High-level architecture

```
Arduino (sensors)
   │  USB serial
   ▼
Python pipeline (laptop, /dev/ttyACM0)
   │  boto3
   ▼
AWS DynamoDB (SensorReadings table, eu-central-1)
   │
   ├──▶ Lambda Function URL (sensor-api) ──▶ Android app (Kotlin/Compose)
   │
   └──▶ (planned) DynamoDB Streams ──▶ Lambda ──▶ Firebase Cloud Messaging ──▶ push notifications
```

The Android app never talks to DynamoDB directly — all access goes through the Lambda-backed API, to avoid embedding AWS credentials in the app.

---

## 2. Hardware

### Kit used
"Upgraded Learning Kit" — Arduino Uno R3 plus a large assortment of modules (RFID, keypad, joystick, stepper motor, servo, matrix/7-segment displays, remote, various sensors, etc.). Only a subset is used in this project.

### Components actually in use

| Component | Purpose | Pin | Source |
|---|---|---|---|
| Arduino Uno R3 | Main board | — | Kit |
| DHT11 | Temperature + humidity | Digital pin 2 | Kit |
| Sound sensor (HW-484, KY-038 family) | Sound level | Analog A0 | Kit |
| Photoresistor (LDR) | Ambient light level | Analog A1 | Kit (bare component, needs resistor) |
| Flame sensor (bare 2-leg phototransistor/photodiode) | Flame detection | Analog A5 | Kit (bare component, needs resistor) |
| 3x status LEDs (red/yellow/green) | Visual traffic-light alert | Pins 4, 5, 6 | Kit |
| Buzzer | Audible alarm | Pin 7 | Kit |
| 9V battery + DC barrel connector | Standalone power (not yet used) | Arduino power jack | Kit |
| **MQ-135 gas sensor** | Air quality (CO2, ammonia, benzene, smoke, etc.) | Analog A2 | **Purchased separately, not yet delivered** |

### Resistor identification (from kit, unlabeled/ambiguous by sight)
Three resistor values were identified by color bands (approximate, confirm with multimeter if in doubt):
- ~100Ω — used for the 3 status LEDs (current-limiting)
- ~1kΩ — spare
- ~10kΩ — used for LDR and flame sensor voltage dividers

### Wiring notes
- **LDR**: 5V → LDR → tap to A1 → 10kΩ resistor → GND (voltage divider). Higher raw ADC value = more light.
- **Flame sensor** (bare component, NOT a pre-built digital module): long leg (anode, +) → 5V; short leg (cathode, −) → A5 AND through 10kΩ resistor → GND. Legs were tested and found to need swapping from the "standard" orientation — see debugging log below. Behavior confirmed: **higher raw ADC value = flame present** (resting ~3, flame close-range ~700).
- **LEDs**: each needs its own current-limiting resistor (~100Ω) between Arduino pin and LED anode.
- **Buzzer/Sound/Gas sensor modules**: pre-built modules, no external resistor needed — just VCC/GND/signal pin.
- **Breadboard note**: this specific breadboard has a **split ground rail** (physically disconnected halves) — the two halves must be bridged with a jumper wire if components on both halves need to share ground. This caused a major debugging detour (see below).

### Standalone Arduino operation (researched, not yet implemented)
Discussed as a future step if parts are purchased:
- **Flash memory** (32KB, Uno) stores the compiled sketch permanently — Arduino does NOT need a laptop to run once flashed.
- **SRAM** (2KB) is volatile working memory, wiped on power loss.
- **EEPROM** (1KB) is small and has limited write cycles — not suitable for continuous logging.
- **For actual standalone logging**: needs an SD card module (SPI, `SD.h` library). Any 2GB–8GB microSD card is more than sufficient (estimated ~35KB/day, ~13MB/year at current cadence) — format as **FAT32**, avoid cards >32GB to sidestep exFAT compatibility issues with the basic SD library.
- **For standalone network connectivity**: would need a WiFi module (ESP8266 shield or switch to ESP32), Bluetooth (HC-05/06, short range only), or GSM/LoRa for remote deployments.
- **Power**: the kit's 9V battery + DC connector can power the Arduino standalone, but 9V batteries have low capacity (~400-600mAh) — expect hours, not days, of runtime, especially with LEDs/buzzer active. A rechargeable LiPo pack would be a better long-term choice.
- **RTC module**: available in the kit but determined to be **not worth adding currently**, since the Python pipeline already timestamps using the laptop's (NTP-synced) clock, and the whole pipeline currently requires the laptop to be connected anyway. Would become worthwhile only if the Arduino is later made to run fully standalone.

---

## 3. Calibration Data

### Sound sensor (HW-484) → decibel conversion
Measured 4 calibration points using a phone dB meter app alongside the Arduino:

| Raw ADC | Measured dB |
|---|---|
| ~170-230 | ~40 |
| ~390 | ~60 |
| ~500 | ~70 |
| ~660 | ~80 |

Fitted logarithmic curve (R² ≈ 0.996):
```
dB = 33.39 * ln(raw) - 137.59
```
Implemented in `raw_to_db()` in the Python pipeline. Valid only within the measured range (~40-80dB); unreliable outside it. Calibrated against a phone app, not a lab-grade reference — good for relative/trend tracking, not certified accuracy.

### Light sensor (LDR)
Left as **relative raw ADC values** (0-1023), not converted to lux. Decision: not worth calibrating for this project's purposes — raw values are being used as contextual/relative data (e.g. "darker than usual") rather than requiring absolute photometric units.

### Flame sensor
- Initial wiring (as per "standard" long-leg/short-leg convention) produced a flat, unusable signal (0→10 max swing).
- **Fix**: swapped the two legs. After swapping: resting value ~3, value with flame held close ~700 — usable signal.
- Threshold set to **150** (deliberately below the 350 midpoint, to stay sensitive to flames not directly adjacent to the sensor, while remaining safely above the ~3 resting noise floor).
- Comparison direction: `flameRaw > FLAME_THRESHOLD` (value increases with flame present).

### Gas sensor (MQ-135)
Not yet calibrated — sensor not yet delivered. Code has placeholder thresholds (`GAS_HIGH = 400` on Arduino, `GAS_ALERT_HIGH = 400` in Python) clearly marked to be replaced once the sensor's actual clean-air baseline is measured after its warm-up period.

---

## 4. Arduino Sketch (current, in `arduino/sensor_sketch.ino`)

Full current contents:

```cpp
#include "DHT.h"

#define DHTPIN 2
#define DHTTYPE DHT11
#define SOUND_PIN A0
#define LDR_PIN A1
#define GAS_PIN A2
#define FLAME_PIN A5
#define LED_GREEN 4
#define LED_YELLOW 5
#define LED_RED 6
#define BUZZER_PIN 7

DHT dht(DHTPIN, DHTTYPE);

// ---- Thresholds for instant local feedback ----
const float TEMP_HIGH = 28.0;
const float HUMIDITY_HIGH = 70.0;
const int SOUND_HIGH = 550;

// ---- Gas sensor (MQ-135) threshold ----
// PLACEHOLDER — needs recalibration once sensor is installed and warmed up.
const int GAS_HIGH = 400;

// ---- Flame sensor threshold ----
// Calibrated using measured values: resting ~3, flame (close range) ~700.
const int FLAME_THRESHOLD = 150;

void setup() {
  Serial.begin(9600);
  dht.begin();
  pinMode(LED_GREEN, OUTPUT);
  pinMode(LED_YELLOW, OUTPUT);
  pinMode(LED_RED, OUTPUT);
  pinMode(BUZZER_PIN, OUTPUT);
}

void setStatusLED(bool bad, bool warn) {
  digitalWrite(LED_RED, bad ? HIGH : LOW);
  digitalWrite(LED_YELLOW, (!bad && warn) ? HIGH : LOW);
  digitalWrite(LED_GREEN, (!bad && !warn) ? HIGH : LOW);
}

void loop() {
  delay(2000);

  float h = dht.readHumidity();
  float t = dht.readTemperature();
  int soundLevel = analogRead(SOUND_PIN);
  int lightLevel = analogRead(LDR_PIN);
  int gasLevel = analogRead(GAS_PIN);
  int flameRaw = analogRead(FLAME_PIN);

  bool flameDetected = (flameRaw > FLAME_THRESHOLD);

  if (isnan(h) || isnan(t)) {
    Serial.println("Failed to read from DHT11 sensor!");
    return;
  }

  bool bad = flameDetected;
  bool warn = (t > TEMP_HIGH || h > HUMIDITY_HIGH || soundLevel > SOUND_HIGH || gasLevel > GAS_HIGH);

  setStatusLED(bad, warn);

  if (bad) {
    tone(BUZZER_PIN, 1000);  // continuous until noTone()
  } else {
    noTone(BUZZER_PIN);
  }

  Serial.print("Humidity: ");
  Serial.print(h);
  Serial.print("%  Temperature: ");
  Serial.print(t);
  Serial.print("°C , Sound Level: ");
  Serial.print(soundLevel);
  Serial.print(", Light: ");
  Serial.print(lightLevel);
  Serial.print(", Gas: ");
  Serial.print(gasLevel);
  Serial.print(", Flame: ");
  Serial.print(flameDetected ? 1 : 0);
  Serial.print(", FlameRaw: ");
  Serial.print(flameRaw);
  Serial.print("\n");
}
```

**⚠️ IMPORTANT DISCREPANCY TO RESOLVE:** the physical Arduino currently connected to the laptop is running an **older version** of this sketch — one **without** the `Gas:` field, since the MQ-135 hasn't arrived yet and wasn't wired in when it was last flashed. The Python pipeline was deliberately made tolerant of this (the `Gas:` field is optional in the parsing regex) so both versions work. **Once the gas sensor arrives, re-flash the Arduino with this current sketch version** (already gas-sensor-ready) — no further sketch changes should be needed at that point, just wiring + threshold calibration.

### Separate test sketch: `arduino/flame_sensor_test.ino`
Used only for calibration — prints raw `analogRead(A5)` values in a loop. Not part of the main pipeline; useful again if recalibrating.

---

## 5. Python Pipeline (`pipeline/sensor_pipeline.py`)

### Responsibilities
1. Reads serial data from Arduino (`/dev/ttyACM0`, 9600 baud).
2. Parses each line via regex (tolerant of missing `Gas:` field).
3. Validates each reading against sane ranges (rejects likely sensor glitches).
4. Operates in a **collect/idle cycle**: collects readings for `AVERAGING_WINDOW_SECONDS` (60s), then goes idle for `IDLE_SECONDS` (180s) — during idle, it keeps draining the serial buffer (reading and discarding) rather than stopping reads entirely, to prevent OS serial buffer overflow.
5. Averages all valid readings in a collecting window (raw values averaged first, then sound converted to dB from the averaged raw value — NOT by averaging individual dB values, since dB is a log scale).
6. Flame detection is NOT averaged — if flame was detected in ANY reading during the window, `flame_detected` is `True` for that whole window (averaging would hide a real brief detection).
7. Writes one averaged row per completed collection window to DynamoDB, with retry/backoff on throttling or network errors.
8. Evaluates window-level alert thresholds (separate constants from the Arduino's own instant thresholds) and prints alerts to console — this is the designated hook point for a future real notification (SNS/email/Slack/etc.).

### Key config values
```python
SERIAL_PORT = "/dev/ttyACM0"
BAUD_RATE = 9600
DEVICE_ID = "arduino-01"
AWS_REGION = "eu-central-1"
TABLE_NAME = "SensorReadings"
AVERAGING_WINDOW_SECONDS = 60
IDLE_SECONDS = 180
```

### DynamoDB item shape written per window
```json
{
  "device_id": "arduino-01",
  "timestamp": 1721838000,        // = window_start, used as sort key
  "window_start": 1721838000,
  "window_end": 1721838060,
  "temperature": 23.4,
  "humidity": 48.0,
  "sound_raw": 412.0,
  "sound_db": 61.6,
  "light_raw": 310.0,
  "gas_raw": null,                 // null until gas sensor installed
  "flame_raw": 3.0,
  "flame_detected": false,
  "alerts": [],                    // list of human-readable alert strings
  "sample_count": 28,
  "rejected_count": 1
}
```

### Full current script
See `pipeline/sensor_pipeline.py` in the repo for the exact, current, tested version (too long to duplicate in full here without redundancy — key logic summarized above).

---

## 6. AWS Setup

### IAM

Two separate identities are involved — **do not confuse them** (this caused real debugging time, see below):

| Identity | Type | Used by | Permissions |
|---|---|---|---|
| `arduino-air-quality` | IAM **User** | Python pipeline script, running on the laptop, authenticated via `aws configure` | `AmazonDynamoDBFullAccess` |
| `sensor-api-role-kcvdqwkp` | IAM **Role** | Auto-created by AWS when the `sensor-api` Lambda function was created | `AmazonDynamoDBReadOnlyAccess` (attached manually after initial 403 errors) |

Local AWS CLI config: `~/.aws/config` → `region = eu-central-1` (set via `aws configure set region eu-central-1`).

### DynamoDB

- **Table name:** `SensorReadings`
- **Region:** `eu-central-1` (Frankfurt) — chosen because that's where the table was originally created via the console; **this caused a real bug** (see debugging log — `us-central-1` typed by mistake is not even a valid AWS region).
- **Partition key:** `device_id` (String)
- **Sort key:** `timestamp` (Number)
- **Billing mode:** On-demand (`PAY_PER_REQUEST`) — no capacity planning needed, free-tier friendly for this data volume.
- **No S3 involved** — this was a point of confusion initially; DynamoDB has its own fully-managed internal storage and is NOT backed by or linked to any S3 bucket. S3 only comes into play for optional export/import features, not used here.

### Lambda — Backend API (`backend/lambda_function.py`)

- **Function name:** `sensor-api`
- **Runtime:** Python 3.12
- **Handler:** `lambda_function.handler` (⚠️ initially misconfigured as the default `lambda_function.lambda_handler`, which doesn't match the actual function name `handler` — caused a 502 error, see debugging log)
- **Environment variables:**
  - `TABLE_NAME = SensorReadings`
  - `SHARED_SECRET = <random string>` — basic protection since the Function URL auth type is `NONE`; not real authentication, just keeps random internet traffic out. Passed as `?key=...` query param on every request.
- **Function URL:** enabled, auth type `NONE`, protected only by the shared-secret query param check in code.
- **Endpoints implemented:**
  - `GET /latest?key=...&device_id=arduino-01` → most recent DynamoDB item for that device
  - `GET /history?key=...&device_id=arduino-01&start=<unix_ts>&end=<unix_ts>` → all items in that timestamp range (both `start` and `end` currently **required**, no default range yet — flagged as a future improvement, see Next Steps)

Full current script:
```python
import json
import os
import boto3
from decimal import Decimal

dynamodb = boto3.resource("dynamodb")
TABLE_NAME = os.environ.get("TABLE_NAME", "SensorReadings")
table = dynamodb.Table(TABLE_NAME)
SHARED_SECRET = os.environ.get("SHARED_SECRET", "")

def decimal_to_native(obj):
    if isinstance(obj, list):
        return [decimal_to_native(v) for v in obj]
    if isinstance(obj, dict):
        return {k: decimal_to_native(v) for k, v in obj.items()}
    if isinstance(obj, Decimal):
        return int(obj) if obj % 1 == 0 else float(obj)
    return obj

def response(status_code, body_dict):
    return {
        "statusCode": status_code,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps(decimal_to_native(body_dict)),
    }

def handler(event, context):
    params = event.get("queryStringParameters") or {}
    if SHARED_SECRET and params.get("key") != SHARED_SECRET:
        return response(401, {"error": "unauthorized"})

    path = event.get("rawPath", "")
    device_id = params.get("device_id", "arduino-01")

    if path.endswith("/latest"):
        return get_latest(device_id)
    elif path.endswith("/history"):
        return get_history(device_id, params)
    else:
        return response(404, {"error": "not found", "path": path})

def get_latest(device_id):
    result = table.query(
        KeyConditionExpression="device_id = :d",
        ExpressionAttributeValues={":d": device_id},
        ScanIndexForward=False,
        Limit=1,
    )
    items = result.get("Items", [])
    if not items:
        return response(404, {"error": "no data found for this device"})
    return response(200, items[0])

def get_history(device_id, params):
    start = params.get("start")
    end = params.get("end")
    if not start or not end:
        return response(400, {"error": "start and end query params required (unix timestamps)"})
    try:
        start = int(start)
        end = int(end)
    except ValueError:
        return response(400, {"error": "start and end must be integers (unix timestamps)"})
    result = table.query(
        KeyConditionExpression="device_id = :d AND #ts BETWEEN :s AND :e",
        ExpressionAttributeNames={"#ts": "timestamp"},
        ExpressionAttributeValues={":d": device_id, ":s": start, ":e": end},
    )
    return response(200, {"items": result.get("Items", [])})
```

**Not yet built:** DynamoDB Streams trigger → Lambda → Firebase Cloud Messaging push notification pipeline (planned, see Next Steps).

---

## 7. Android App

### Environment setup
- **OS:** Ubuntu 24.04
- **IDE:** Android Studio (installed via `sudo snap install android-studio --classic`)
- **Language:** Kotlin
- **UI framework:** Jetpack Compose
- **Test device:** Nothing Phone 2 (physical device via USB debugging, NOT the emulator — chosen for better performance and realistic testing conditions, especially relevant later for real push notifications)
- **Repo location:** `~/Github/AirQualityV2/android-app/` (created inside the existing monorepo, not a separate repo)

### Learning path chosen
User has zero prior mobile dev experience and explicitly chose the **"learn properly, higher complexity"** path over the fastest/simplest option — i.e., native Kotlin + Compose rather than a lower-effort alternative (e.g. a web dashboard), understanding this is a multi-week undertaking done properly. Currently working through Google's official Compose fundamentals course in parallel with hands-on building.

### Current build state
1. `SensorReading.kt` — a Kotlin data class mirroring the DynamoDB item shape, plus a `MockData` object (used briefly, now being replaced with real API calls).
2. `StatusScreen.kt` — a Composable function rendering:
   - A colored circle (traffic light: red/amber/green based on the `status` field)
   - Online/offline text, derived by comparing the reading's `window_start` timestamp against current time (threshold: offline if last reading >8 minutes old, roughly 2x the expected ~4-minute cycle)
   - A card showing temperature, humidity, sound (dB), light (raw), gas (raw or "sensor not installed"), and flame detected (yes/no)
3. Wired into `MainActivity.kt`'s `setContent { }` block.
4. **In progress:** replacing `MockData` with a real network call to the Lambda Function URL's `/latest` endpoint (via Retrofit — not yet implemented at time of writing).

### Not yet built
- Retrofit networking layer / real API integration (in progress)
- History screen with date/time range picker
- Charting (candidate libraries: Vico or MPAndroidChart — not yet chosen)
- Firebase Cloud Messaging integration for push notifications
- Navigation between screens (currently single-screen)
- Settings/threshold configuration screen

---

## 8. Debugging Log (chronological, all issues encountered and resolved)

1. **`pip install` → externally-managed-environment error.**
   Fix: use `pip install <package> --break-system-packages`, or a virtual environment.

2. **`TypeError: Float types are not supported. Use Decimal types instead.`** (boto3 DynamoDB write)
   Cause: boto3's DynamoDB resource layer rejects native Python `float`. Fix: convert all floats to `Decimal(str(value))` (via `str()` first to avoid floating-point imprecision artifacts). Implemented as a recursive `floats_to_decimal()` helper.

3. **`ResourceNotFoundException` on `PutItem`.**
   Cause: table existed in a different AWS region than the one boto3/CLI defaulted to. Fixed by explicitly matching `region_name="eu-central-1"` in code and via `aws configure set region eu-central-1`.

4. **`EndpointConnectionError: Could not connect to ... dynamodb.us-central-1.amazonaws.com`.**
   Cause: **`us-central-1` is not a valid AWS region at all** (typo/confusion with `eu-central-1` or `us-east-1`) — hardcoded directly in the script, overriding the (correct) `~/.aws/config` default. Fix: corrected the hardcoded `region_name` in the script itself.

5. **AWS CLI installation via apt vs. official installer.**
   Chose AWS's official installer script (curl + unzip + install) over apt (version lag) or pip (not recommended by AWS for the CLI specifically).

6. **`snap install android-studio` got stuck** after being cancelled mid-install (`error: snap "android-studio" has "install-snap" change in progress`).
   Fix sequence: `snap changes android-studio` → identify stuck change ID → `sudo snap abort <ID>` → retry install; when that didn't fully resolve it, `sudo systemctl restart snapd` and retry; full reset path (`snap remove` → restart snapd → reinstall) was the documented fallback if needed.

7. **`adb devices` — `command not found`.**
   Cause: `adb` lives inside the Android SDK folder (`~/Android/Sdk/platform-tools/`), not on PATH by default. Fixed by adding it to `~/.bashrc`.

8. **`adb devices` — `no permissions (missing udev rules? user is in the plugdev group)`.**
   Fix attempted: add user to `plugdev` group, install community udev rules (`android-udev-rules` project), reload udev, restart adb server, log out/in.
   **Actual root cause (discovered after the above didn't fully resolve it): the phone was connected via a USB docking station, not directly to the laptop.** Docks can fail to pass through full USB data/ADB functionality even when charging/file-transfer appear to work. **Fix: connect the phone directly to a laptop USB port**, bypassing the dock entirely — resolved immediately.

9. **Flame sensor: floating/noisy analog readings** (smooth ramp 0→1023→0 pattern, classic floating-pin signature), **persisting even with the sensor physically removed from the circuit**, and **persisting even wired directly to GND on a different pin (A5)**.
   Root cause: **the breadboard's ground rail is physically split into two disconnected halves**, and the Arduino's GND connection and the sensor circuit's GND tap were on opposite, unbridged halves. Confirmed via a direct Arduino-pin-to-Arduino-pin jumper test (bypassing the breadboard entirely), which read a clean, stable `0`. Fix: bridge both halves of the breadboard's power/ground rails with a jumper wire.

10. **Flame sensor: still very weak signal (0→10 max swing) even after the grounding fix.**
    Cause: component (a bare 2-leg phototransistor/photodiode) was wired in reverse polarity. Fix: swapped the long/short legs. Resulting signal: resting ~3, flame-present (close range) ~700 — usable.

11. **Lambda Function URL returning invalid/non-JSON response (`JSON.parse: unexpected character`) when opened directly in a browser.**
    Diagnosed by switching to `curl -i` (to see actual status code/headers, which a browser tab hides) and checking CloudWatch Logs.

12. **Lambda returning 502 Bad Gateway.**
    Root cause #1: **Handler misconfiguration** — Lambda's configured handler (`lambda_function.lambda_handler`) didn't match the actual function name in the code (`handler`). Fixed via **Configuration → Runtime settings → Edit → Handler: `lambda_function.handler`**.
    Root cause #2 (after fixing #1, still 502): **`AccessDeniedException` on `dynamodb:Query`** — the Lambda's auto-generated execution role (`sensor-api-role-kcvdqwkp`) did not actually have `AmazonDynamoDBReadOnlyAccess` attached, despite believing it had been added earlier (the attach step likely didn't fully complete/save the first time). Fixed by re-attaching the policy directly on that specific IAM **role** (confirmed via CloudWatch Logs traceback, which pinpointed the exact denied action and role ARN).
    **Point of confusion during this debugging**: briefly conflated the Lambda's auto-created execution **role** (`sensor-api-role-kcvdqwkp`) with the separate, pre-existing IAM **user** (`arduino-air-quality`) used by the Python pipeline. These are two entirely separate identities with independent permissions — fixing one does not affect the other.

13. **`/history` endpoint returning `"start and end query params required"`.**
    Not a bug — this is enforced intentionally in the current code (both params are mandatory, no default range implemented yet). Correct usage requires explicit Unix timestamps for both `start` and `end`, e.g. via `date +%s` and `date -d '24 hours ago' +%s`. Flagged for a future improvement (default to a sensible range, e.g. last 24h, if omitted).

---

## 9. Current Status Summary (as of Aug 2, 2026)

**Working:**
- Arduino reads DHT11, sound, LDR, flame (calibrated) continuously.
- Python pipeline runs the full collect(60s)/idle(180s) cycle, validates, averages, and writes to DynamoDB reliably, with retry/backoff on transient failures.
- Baseline sensor data now exists in DynamoDB.
- Lambda backend API (`/latest`, `/history`) is deployed and confirmed working end-to-end via `curl`.
- Android Studio + Kotlin/Compose dev environment fully set up, physical device (Nothing Phone 2) connected via ADB.
- First Compose screen (`StatusScreen.kt`) built and renders correctly with data.

**Pending / not yet done:**
- Gas sensor (MQ-135) has been purchased but not yet delivered/wired/calibrated. Arduino code and Python pipeline are already prepared for it (gas fields optional until installed).
- Physical Arduino currently still running the pre-gas-sensor sketch version — needs re-flashing once the MQ-135 arrives.
- Android app: real Retrofit API integration (replacing mock data) — in progress at time of writing.
- Android app: history screen, charts, navigation, Firebase push notifications — not started.
- DynamoDB Streams → Lambda → FCM push notification pipeline — designed conceptually, not built.
- `/history` endpoint default date range — not implemented.
- Nicer/friendlier timestamp query format for the API (currently raw Unix timestamps only) — flagged as a future improvement.
- Standalone Arduino operation (SD card, WiFi module, RTC) — researched, no parts purchased yet beyond what's already in the kit.

---

## 10. Next Steps (in rough priority order)

1. Finish wiring the Android app's `StatusScreen` to the real Lambda `/latest` endpoint via Retrofit.
2. Build a history screen with a date/time range picker calling `/history`, plus a charting library integration.
3. Set up DynamoDB Streams + a notification Lambda + Firebase Cloud Messaging for real push notifications on flame detection.
4. When the MQ-135 arrives: wire to A2, run warm-up period, calibrate `GAS_HIGH`/`GAS_ALERT_HIGH` against a measured clean-air baseline, re-flash Arduino.
5. Improve `/history` API: sensible default range when `start`/`end` omitted; friendlier timestamp input format.
6. Longer-term/optional: standalone Arduino operation (SD card logging and/or WiFi module for laptop-independent operation), RTC module (only if going standalone).
