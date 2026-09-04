import os
import serial
from serial.tools import list_ports
import time
import math
import re
import boto3
import requests
from botocore.exceptions import ClientError, EndpointConnectionError
from decimal import Decimal

# ---------- CONFIG ----------
# Leave as None to find the board automatically by its USB identity. Set it to a path
# like "/dev/ttyACM0" only to force a specific port (e.g. two boards plugged in at once).
#
# Auto-detection exists because the number on the end of /dev/ttyACM* is not stable: the
# kernel hands out the next free one, so replugging the board, or a reset while the old
# node is still held, moves it from ttyACM0 to ttyACM1 and a hard-coded path then fails
# with "No such file or directory" even though the Arduino is sitting right there.
SERIAL_PORT = None

# USB vendor IDs that mean "this is probably the board": Arduino SA and Arduino.org, plus
# the USB-serial chips used on clones (CH340, FTDI, CP210x, SparkFun, Adafruit).
KNOWN_BOARD_VIDS = {0x2341, 0x2A03, 0x1A86, 0x0403, 0x10C4, 0x1B4F, 0x239A}
BAUD_RATE = 9600
# readline() returns whatever it has so far when this expires, so a value at or below the
# Arduino's ~2s loop period clips lines in half. Kept comfortably above it; the cost is
# that a silent Arduino delays the collect/idle phase switch by up to this many seconds.
SERIAL_TIMEOUT_SECONDS = 5
DEVICE_ID = "arduino-01"
AWS_REGION = "eu-central-1"
TABLE_NAME = "SensorReadings"

AVERAGING_WINDOW_SECONDS = 30   # how long to actively collect readings before averaging
IDLE_SECONDS = 60              # how long to pausebetween windows

# ---------- NOTIFICATIONS ----------
# A flame is the ONLY condition that reaches a phone. Everything else stays in the app on
# purpose: a notification you learn to swipe away is worse than no notification at all.
#
# Both channels are optional and independent, set either, both, or neither. Leaving both
# as None disables notifications entirely and the pipeline behaves exactly as before.

# All four notification settings come from the environment rather than being written
# here, because LAMBDA_KEY is the Lambda's shared secret and this file is in git. Set them
# in your shell (or a .env you don't commit) before starting the pipeline:
#
#   export AIRQ_LAMBDA_URL="https://<id>.lambda-url.eu-central-1.on.aws"
#   export AIRQ_LAMBDA_KEY="<the Lambda's SHARED_SECRET>"
#   export AIRQ_SNS_TOPIC_ARN="arn:aws:sns:eu-central-1:<account>:AirQualityAlerts"
#   export AIRQ_NTFY_TOPIC="<something unguessable>"
#
# Every one of them is optional. Unset means that piece is simply switched off, and the
# pipeline runs exactly as it did before notifications existed.

# Where the app's notification preferences are read from. Unset ignores the app entirely
# and falls back to the local channel settings below.
LAMBDA_BASE_URL = os.environ.get("AIRQ_LAMBDA_URL") or None
LAMBDA_KEY = os.environ.get("AIRQ_LAMBDA_KEY") or None
PREFS_REFRESH_SECONDS = 240  # re-read once per collect/idle cycle

# ntfy: the pipeline POSTs to a topic, you subscribe to that topic in the ntfy app.
# The topic name IS the credential, anyone who knows it can read your alerts and post
# to them, so use something unguessable, not "airquality".
NTFY_SERVER = os.environ.get("AIRQ_NTFY_SERVER", "https://ntfy.sh")
NTFY_TOPIC = os.environ.get("AIRQ_NTFY_TOPIC") or None

# SNS email. The address must confirm the subscription by clicking the link AWS emails it
# before anything is delivered, and the pipeline's IAM user needs sns:Publish.
SNS_TOPIC_ARN = os.environ.get("AIRQ_SNS_TOPIC_ARN") or None

# Flame readings arrive every ~2s, so a fire that keeps burning would notify on every one
# of them. Notify on the transition into flame, then at most once per re-notify interval.
FLAME_RENOTIFY_SECONDS = 1800   # remind every 30 min while it is still detected
# Declare it over only after this long with no detection, so a flickering sensor reading
# 1,0,1,0 produces one alert rather than an alternating stream of alerts and all-clears.
FLAME_CLEAR_SECONDS = 60


# ---------- VALIDATION RANGES ----------
# DHT11 datasheet limits + a sane range for the sound sensor's raw ADC value.
# Readings outside these are almost certainly sensor glitches/noise, not real data.
TEMP_MIN, TEMP_MAX = 0, 50          # DHT11 rated range (C)
HUMIDITY_MIN, HUMIDITY_MAX = 20, 90  # DHT11 rated range (%)
SOUND_RAW_MIN, SOUND_RAW_MAX = 0, 1023  # 10-bit ADC range on Arduino Uno
LIGHT_MIN, LIGHT_MAX = 0, 1023          # 10-bit ADC range for the LDR
FLAME_RAW_MIN, FLAME_RAW_MAX = 0, 1023  # 10-bit ADC range for the flame sensor
GAS_RAW_MIN, GAS_RAW_MAX = 0, 1023      # 10-bit ADC range for the MQ-135 gas sensor

# ---------- ALERT THRESHOLDS (evaluated on the window AVERAGE, for logging/notifications) ----------
# These are separate from the Arduino's own instant per-reading thresholds ,
# this set looks at trends over the averaging window rather than single spikes.
TEMP_ALERT_HIGH = 28.0
HUMIDITY_ALERT_HIGH = 50.0
SOUND_DB_ALERT_HIGH = 75.0
LIGHT_ALERT_HIGH = 800  # matches Arduino's LIGHT_HIGH, relative to your LDR/resistor pairing
FLAME_ALERT_ANY = True  # if True, any flame detection in the window triggers an alert
# PLACEHOLDER, set this once you've measured your MQ-135's actual clean-air
# baseline after its warm-up period. 400 is just a starting guess.
GAS_ALERT_HIGH = 400

# ---------- DB CONVERSION ----------
def raw_to_db(raw_value):
    """Convert raw analog sound sensor reading to estimated dB.
    Calibrated specifically for this HW-484 sensor using measured points:
    (200,40) (390,60) (500,70) (660,80)
    """
    if raw_value <= 0:
        return None
    return round(33.39 * math.log(raw_value) - 137.59, 1)


# ---------- HELPERS ----------
def floats_to_decimal(obj):
    """Recursively convert floats to Decimal for DynamoDB compatibility."""
    if isinstance(obj, float):
        return Decimal(str(obj))
    if isinstance(obj, dict):
        return {k: floats_to_decimal(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [floats_to_decimal(v) for v in obj]
    return obj


# Matches with or without the "Gas:" field. The gas sensor is installed now, but rows
# predating it exist and an older sketch may still be flashed:
# "Humidity: 48.00%  Temperature: 22.50°C , Sound Level: 412, Light: 300, Gas: 250, Flame: 0, FlameRaw: 320"
# "Humidity: 48.00%  Temperature: 22.50°C , Sound Level: 412, Light: 300, Flame: 0, FlameRaw: 320"
#
# Anchored at BOTH ends, with the sketch's literal "," separators rather than ".*?"
# between fields. That is deliberate and load-bearing: a serial read can return half a
# line (see SERIAL_TIMEOUT_SECONDS), and with permissive gaps a head glued to the next
# full line still matched, ".*?" would backtrack past the second "Humidity:" and stitch
# temperature from one reading to light/gas from the next, producing a plausible blended
# row that passed validation and got stored. Anchoring alone does not prevent that; the
# strict separators are what do. Keep both if you edit the line format.
#
# "[^,]*" after the temperature absorbs the "°C " suffix, which survives the decode as
# either "°C" or a bare "C" depending on how the multi-byte degree sign lands.
LINE_PATTERN = re.compile(
    r"\A\s*Humidity:\s*([\d.]+)%\s+Temperature:\s*([\d.]+)[^,]*,\s*"
    r"Sound Level:\s*(\d+),\s*Light:\s*(\d+)"
    r"(?:,\s*Gas:\s*(\d+))?,\s*"
    r"Flame:\s*(\d),\s*FlameRaw:\s*(\d+)\s*\Z"
)


def parse_line(line):
    """Expects Arduino output like:
    'Humidity: 48.00%  Temperature: 22.50°C , Sound Level: 412, Light: 300, Gas: 250, Flame: 0, FlameRaw: 320'
    (Gas field is optional, will be None if not present in the line, e.g.
    before the gas sensor is physically installed.)
    """
    match = LINE_PATTERN.match(line)
    if not match:
        return None
    try:
        humidity = float(match.group(1))
        temperature = float(match.group(2))
        sound_raw = int(match.group(3))
        light_raw = int(match.group(4))
        gas_raw = int(match.group(5)) if match.group(5) is not None else None
        flame = int(match.group(6))       # Arduino's own thresholded 0/1
        flame_raw = int(match.group(7))   # raw ADC value, for logging/recalibration
        return temperature, humidity, sound_raw, light_raw, gas_raw, flame, flame_raw
    except ValueError:
        return None


def is_valid_reading(temperature, humidity, sound_raw, light_raw, gas_raw, flame, flame_raw):
    """Basic range/sanity checks. Rejects readings that are almost certainly
    glitches (sensor noise, disconnected wire, brownout, etc.) rather than
    real measurements. Adjust ranges as you learn your sensors' real behavior."""
    if not (TEMP_MIN <= temperature <= TEMP_MAX):
        return False, f"temperature {temperature} out of range"
    if not (HUMIDITY_MIN <= humidity <= HUMIDITY_MAX):
        return False, f"humidity {humidity} out of range"
    if not (SOUND_RAW_MIN <= sound_raw <= SOUND_RAW_MAX):
        return False, f"sound_raw {sound_raw} out of range"
    if not (LIGHT_MIN <= light_raw <= LIGHT_MAX):
        return False, f"light_raw {light_raw} out of range"
    if gas_raw is not None and not (GAS_RAW_MIN <= gas_raw <= GAS_RAW_MAX):
        return False, f"gas_raw {gas_raw} out of range"
    if not (FLAME_RAW_MIN <= flame_raw <= FLAME_RAW_MAX):
        return False, f"flame_raw {flame_raw} out of range"
    if flame not in (0, 1):
        return False, f"flame value {flame} not 0 or 1"
    return True, None


def check_alerts(avg_temp, avg_humidity, avg_sound_db, avg_light_raw, avg_gas_raw, flame_detected_in_window):
    """Returns a list of human-readable alert messages for any threshold
    crossed by this window's averaged values. Empty list = all clear."""
    alerts = []
    if avg_temp > TEMP_ALERT_HIGH:
        alerts.append(f"Temperature high: {avg_temp:.1f}C (threshold {TEMP_ALERT_HIGH}C)")
    if avg_humidity > HUMIDITY_ALERT_HIGH:
        alerts.append(f"Humidity high: {avg_humidity:.1f}% (threshold {HUMIDITY_ALERT_HIGH}%)")
    if avg_light_raw > LIGHT_ALERT_HIGH:
        alerts.append(f"Light level high: {avg_light_raw:.1f} raw (threshold {LIGHT_ALERT_HIGH})")
    if avg_sound_db is not None and avg_sound_db > SOUND_DB_ALERT_HIGH:
        alerts.append(f"Sound level high: {avg_sound_db:.1f}dB (threshold {SOUND_DB_ALERT_HIGH}dB)")
    if avg_gas_raw is not None and avg_gas_raw > GAS_ALERT_HIGH:
        alerts.append(f"Gas level high: {avg_gas_raw:.1f} raw (threshold {GAS_ALERT_HIGH})")
    if FLAME_ALERT_ANY and flame_detected_in_window:
        alerts.append("Flame detected during this window!")
    return alerts


# None means "never successfully fetched", which is distinct from a fetched-but-empty
# preference. Unknown falls back to the local constants; explicitly-empty means the user
# turned notifications off in the app and must NOT be overridden by a stale local value.
_prefs = None
_prefs_fetched_at = 0.0


def refresh_prefs(force=False):
    """Pull the app's notification settings. Failure is non-fatal, the last known good
    settings stay in effect, because dropping to 'no channels' the moment the network
    blips is exactly when an alert matters most."""
    global _prefs, _prefs_fetched_at
    if not LAMBDA_BASE_URL:
        return
    now = time.time()
    if not force and now - _prefs_fetched_at < PREFS_REFRESH_SECONDS:
        return
    try:
        r = requests.get(
            f"{LAMBDA_BASE_URL.rstrip('/')}/prefs",
            params={"key": LAMBDA_KEY, "device_id": DEVICE_ID},
            timeout=5,
        )
        r.raise_for_status()
        fetched = r.json()
        if fetched != _prefs:
            print(f"Notification prefs: channels={fetched.get('channels')} "
                  f"email_status={fetched.get('email_status')}")
        _prefs = fetched
        _prefs_fetched_at = now
    except Exception as e:                      # noqa: BLE001
        print(f"Could not read notification prefs ({e}); keeping previous settings.")


def active_ntfy_topic():
    """The topic to publish to, or None. App settings win; local constant is the fallback
    only while the app's settings have never been read."""
    if _prefs is None:
        return NTFY_TOPIC
    if "ntfy" in _prefs.get("channels", []):
        return _prefs.get("ntfy_topic") or NTFY_TOPIC
    return None


def email_enabled():
    if _prefs is None:
        return bool(SNS_TOPIC_ARN)
    return "email" in _prefs.get("channels", []) and bool(SNS_TOPIC_ARN)


_sns = None


def _sns_client():
    """Lazily built so the pipeline still runs with no SNS configured or reachable."""
    global _sns
    if _sns is None:
        _sns = boto3.client("sns", region_name=AWS_REGION)
    return _sns


def send_ntfy(title, body):
    topic = active_ntfy_topic()
    if not topic:
        return False
    try:
        requests.post(
            f"{NTFY_SERVER}/{topic}",
            data=body.encode("utf-8"),
            headers={"Title": title, "Priority": "urgent", "Tags": "fire"},
            timeout=5,
        )
        return True
    except Exception as e:                      # noqa: BLE001 - see notify()
        print(f"  ntfy notification failed: {e}")
        return False


def send_sns_email(subject, body):
    if not email_enabled():
        return False
    try:
        # SNS caps Subject at 100 chars and rejects newlines in it.
        _sns_client().publish(TopicArn=SNS_TOPIC_ARN, Subject=subject[:100], Message=body)
        return True
    except Exception as e:                      # noqa: BLE001 - see notify()
        print(f"  SNS notification failed: {e}")
        return False


def notify(title, body):
    """Fan out to every configured channel.

    Deliberately swallows every exception: the pipeline is still recording the event that
    triggered this, and losing the recording because a notification service was
    unreachable would be the worse failure. Failures are printed, not raised.
    """
    if not active_ntfy_topic() and not email_enabled():
        print(f"  [no notification channel configured] {title}: {body}")
        return
    delivered = [name for name, ok in
                 (("ntfy", send_ntfy(title, body)), ("email", send_sns_email(title, body)))
                 if ok]
    print(f"  Notified via {', '.join(delivered)}." if delivered
          else "  Notification failed on every configured channel.")


# Transition state for the flame alert. Kept in memory only: a pipeline restart during an
# active fire re-notifies, which is the right way for this to fail.
_flame_alert = {"active": False, "last_notified": 0.0, "last_detected": 0.0}


def check_flame_alert(flame, flame_raw, now):
    """Evaluate one reading for the instant flame alert.

    Called for every valid reading in BOTH phases, unlike the averaging path. The idle
    phase used to discard lines without parsing them, so a fire starting just after a
    window closed went unseen for up to AVERAGING_WINDOW_SECONDS + IDLE_SECONDS, around
    four minutes. Storage cadence is untouched; only alerting runs continuously.
    """
    if flame == 1:
        _flame_alert["last_detected"] = now
        first = not _flame_alert["active"]
        due = now - _flame_alert["last_notified"] >= FLAME_RENOTIFY_SECONDS
        if first or due:
            _flame_alert["active"] = True
            _flame_alert["last_notified"] = now
            stamp = time.strftime("%H:%M:%S", time.localtime(now))
            print("!!! FLAME DETECTED, sending notification !!!")
            notify(
                "Flame detected" if first else "Flame still detected",
                f"{DEVICE_ID} reported flame at {stamp} (flame_raw={flame_raw}).",
            )
    elif _flame_alert["active"] and now - _flame_alert["last_detected"] >= FLAME_CLEAR_SECONDS:
        _flame_alert["active"] = False
        quiet = int(now - _flame_alert["last_detected"])
        notify("Flame cleared", f"{DEVICE_ID}: no flame for {quiet}s.")


def find_serial_port():
    """Locate the Arduino's serial device, or None if it isn't plugged in.

    Only ports reporting a USB vendor ID are considered, which filters out the ~32
    legacy /dev/ttyS* nodes Linux always advertises. A port whose VID is a known board
    vendor wins; failing that, any USB serial device is taken as a best guess.
    """
    usb_ports = [p for p in list_ports.comports() if p.vid is not None]
    if not usb_ports:
        return None

    known = [p for p in usb_ports if p.vid in KNOWN_BOARD_VIDS]
    candidates = known or usb_ports

    if len(candidates) > 1:
        print("Multiple USB serial devices found; using the first. "
              "Set SERIAL_PORT explicitly to choose:")
        for p in candidates:
            print(f"  {p.device}, {p.description}")

    chosen = candidates[0]
    how = "matched a known board vendor" if known else "only USB serial device present"
    print(f"Auto-detected {chosen.device} ({chosen.description}), {how}.")
    return chosen.device


def connect_serial(max_retries=5):
    """Attempt to open the serial connection, retrying with backoff on failure.
    Returns the open connection, or None if all retries are exhausted."""
    for attempt in range(1, max_retries + 1):
        # Re-resolved every attempt, not once at startup: if the board is replugged
        # mid-run it usually comes back on a different node, and reconnecting to the
        # remembered path would fail forever.
        port = SERIAL_PORT or find_serial_port()
        if port is None:
            wait = min(2 ** attempt, 30)
            print(f"No USB serial device found (attempt {attempt}/{max_retries}). "
                  f"Is the Arduino plugged in? Retrying in {wait}s...")
            time.sleep(wait)
            continue

        try:
            ser = serial.Serial(port, BAUD_RATE, timeout=SERIAL_TIMEOUT_SECONDS)
            time.sleep(2)  # allow Arduino to reset after serial connection opens

            # Opening the port can land in the middle of a line the Arduino was already
            # printing, so whatever is buffered now may be a headless fragment like
            # "76, Gas: 462, Flame: 0, FlameRaw: 51". Drop the buffer and then read one
            # more line to consume any partial that was already in flight, so the first
            # line the main loop parses is guaranteed to start at "Humidity:".
            ser.reset_input_buffer()
            discarded = ser.readline().decode("utf-8", errors="ignore").strip()
            if discarded and not discarded.startswith("Humidity:"):
                print(f"Discarded partial line while syncing: {discarded}")

            print(f"Connected to {port}")
            return ser
        except serial.SerialException as e:
            wait = min(2 ** attempt, 30)  # exponential backoff, capped at 30s
            print(f"Serial connection failed on {port} (attempt {attempt}/{max_retries}): {e}")
            if "Permission denied" in str(e):
                print("  -> That is a permissions problem, not a wiring one: "
                      "add yourself to the 'dialout' group and log back in.")
            print(f"Retrying in {wait}s...")
            time.sleep(wait)
    return None


def write_to_dynamodb(table, item, max_retries=3):
    """Write an item with retry on throttling/transient errors.
    Returns True on success, False if all retries failed."""
    for attempt in range(1, max_retries + 1):
        try:
            table.put_item(Item=item)
            return True
        except ClientError as e:
            error_code = e.response["Error"]["Code"]
            if error_code in ("ProvisionedThroughputExceededException", "ThrottlingException"):
                wait = min(2 ** attempt, 20)
                print(f"DynamoDB throttled (attempt {attempt}/{max_retries}), retrying in {wait}s...")
                time.sleep(wait)
            else:
                # Non-retryable error (e.g. ResourceNotFoundException, validation error)
                print(f"DynamoDB write failed permanently: {error_code} - {e}")
                return False
        except EndpointConnectionError as e:
            wait = min(2 ** attempt, 20)
            print(f"No network connectivity to AWS (attempt {attempt}/{max_retries}): {e}")
            print(f"Retrying in {wait}s...")
            time.sleep(wait)
    print("Giving up on this write after max retries.")
    return False


# ---------- MAIN ----------
def main():
    print(f"Connecting to Arduino on {SERIAL_PORT or 'auto-detected port'}...")
    ser = connect_serial()
    if ser is None:
        print("Could not connect to Arduino after retries. Exiting.")
        return

    dynamodb = boto3.resource("dynamodb", region_name=AWS_REGION)
    table = dynamodb.Table(TABLE_NAME)

    refresh_prefs(force=True)
    print(f"Notifications, ntfy: {'on' if active_ntfy_topic() else 'off'}, "
          f"email: {'on' if email_enabled() else 'off'}"
          f"{'' if LAMBDA_BASE_URL else ' (app prefs not configured; using local settings)'}")

    print(f"Listening for sensor data, {AVERAGING_WINDOW_SECONDS}s collecting, "
          f"then {IDLE_SECONDS}s idle, repeating... (Ctrl+C to stop)")

    is_collecting = True
    phase_start = time.time()
    window_start_ts = int(phase_start)  # marks when the current collecting phase began
    temps, humidities, sounds, lights, gases, flame_raws = [], [], [], [], [], []
    flame_detected_in_window = False
    rejected_count = 0

    while True:
        try:
            raw_line = ser.readline().decode("utf-8", errors="ignore")

            # Every line is now parsed in both phases. Only ACCUMULATION is gated on the
            # collecting phase, the flame check below has to run continuously, or a fire
            # starting during the 180s idle stretch would go unnoticed until the next
            # window closed.
            if raw_line.strip():
                parsed = parse_line(raw_line)
                if parsed is None:
                    # Fragments are expected while draining during idle; only worth
                    # reporting when this line should have counted for something.
                    if is_collecting:
                        print(f"Skipping unparseable line: {raw_line.strip()}")
                else:
                    temperature, humidity, sound_raw, light_raw, gas_raw, flame, flame_raw = parsed
                    valid, reason = is_valid_reading(
                        temperature, humidity, sound_raw, light_raw, gas_raw, flame, flame_raw
                    )
                    if valid:
                        # Alerting path: always on, both phases, ~2s latency.
                        check_flame_alert(flame, flame_raw, time.time())

                        # Storage path: unchanged, collecting phase only.
                        if is_collecting:
                            temps.append(temperature)
                            humidities.append(humidity)
                            sounds.append(sound_raw)
                            lights.append(light_raw)
                            if gas_raw is not None:
                                gases.append(gas_raw)
                            flame_raws.append(flame_raw)
                            if flame == 1:
                                flame_detected_in_window = True
                    elif is_collecting:
                        rejected_count += 1
                        print(f"Rejected reading: {reason}")

            elapsed = time.time() - phase_start
            phase_length = AVERAGING_WINDOW_SECONDS if is_collecting else IDLE_SECONDS

            if elapsed >= phase_length:
                if is_collecting:
                    window_end_ts = int(time.time())

                    if temps:  # only write if we collected at least one valid reading
                        avg_temp = sum(temps) / len(temps)
                        avg_humidity = sum(humidities) / len(humidities)
                        avg_sound_raw = sum(sounds) / len(sounds)
                        avg_light_raw = sum(lights) / len(lights)
                        avg_gas_raw = (sum(gases) / len(gases)) if gases else None
                        avg_flame_raw = sum(flame_raws) / len(flame_raws)
                        # Convert dB from the averaged raw value, not by averaging
                        # individual dB values (log-scale averaging would skew results).
                        avg_sound_db = raw_to_db(avg_sound_raw)

                        alerts = check_alerts(avg_temp, avg_humidity, avg_sound_db, avg_light_raw, avg_gas_raw, flame_detected_in_window)

                        # Explicit traffic light status for the app to read directly,
                        # rather than re-deriving it from the alerts list each time.
                        if flame_detected_in_window:
                            status = "red"
                        elif alerts:
                            status = "yellow"
                        else:
                            status = "green"

                        item = {
                            "device_id": DEVICE_ID,
                            "timestamp": window_start_ts,  # sort key: start of the window
                            "window_start": window_start_ts,
                            "window_end": window_end_ts,
                            "temperature": round(avg_temp, 2),
                            "humidity": round(avg_humidity, 2),
                            "sound_raw": round(avg_sound_raw, 1),
                            "sound_db": avg_sound_db,
                            "light_raw": round(avg_light_raw, 1),
                            "gas_raw": round(avg_gas_raw, 1) if avg_gas_raw is not None else None,
                            "flame_raw": round(avg_flame_raw, 1),
                            "flame_detected": flame_detected_in_window,
                            "alerts": alerts,  # empty list if all clear
                            "status": status,  # "green" / "yellow" / "red", for the app's traffic light
                            "sample_count": len(temps),
                            "rejected_count": rejected_count,
                        }
                        item = floats_to_decimal(item)

                        success = write_to_dynamodb(table, item)
                        if success:
                            print(f"Stored averaged reading from {len(temps)} samples "
                                  f"({rejected_count} rejected): {item}")
                            if alerts:
                                print("!!! ALERT !!!")
                                for a in alerts:
                                    print(f"  - {a}")
                                # This is the hook point for a real notification ,
                                # e.g. send an SNS message, email, or Slack ping here.
                        else:
                            print("Failed to store this averaging window's data, moving on.")
                    else:
                        print(f"No valid readings this window ({rejected_count} rejected), skipping write.")

                    # switch to idle phase
                    is_collecting = False
                    phase_start = time.time()
                    temps, humidities, sounds, lights, gases, flame_raws = [], [], [], [], [], []
                    flame_detected_in_window = False
                    rejected_count = 0
                    print(f"Going idle for {IDLE_SECONDS}s...")

                else:
                    # idle phase ended, start collecting again
                    is_collecting = True
                    phase_start = time.time()
                    window_start_ts = int(phase_start)
                    refresh_prefs()
                    print(f"Collecting again for {AVERAGING_WINDOW_SECONDS}s...")

        except KeyboardInterrupt:
            print("\nStopping.")
            break
        except serial.SerialException as e:
            print(f"Serial connection lost: {e}")
            print("Attempting to reconnect...")
            ser.close()
            ser = connect_serial()
            if ser is None:
                print("Reconnection failed after retries. Exiting.")
                break
        except Exception as e:
            print(f"Unexpected error: {e}")
            time.sleep(1)  # brief pause before continuing, avoid tight error loop

    ser.close()


if __name__ == "__main__":
    main()
