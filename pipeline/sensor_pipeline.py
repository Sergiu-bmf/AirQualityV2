import serial
import time
import math
import re
import boto3
from botocore.exceptions import ClientError, EndpointConnectionError
from decimal import Decimal

# ---------- CONFIG ----------
SERIAL_PORT = "/dev/ttyACM0"   # check with `ls /dev/tty*` while Arduino is plugged in
                                 # (often /dev/ttyUSB0 or /dev/ttyACM0 on Linux)
BAUD_RATE = 9600
DEVICE_ID = "arduino-01"
AWS_REGION = "eu-central-1"
TABLE_NAME = "SensorReadings"

AVERAGING_WINDOW_SECONDS = 60   # how long to actively collect readings before averaging
IDLE_SECONDS = 180              # how long to pause (drain-only, no storage) between windows

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
# These are separate from the Arduino's own instant per-reading thresholds —
# this set looks at trends over the averaging window rather than single spikes.
TEMP_ALERT_HIGH = 28.0
HUMIDITY_ALERT_HIGH = 70.0
SOUND_DB_ALERT_HIGH = 75.0
FLAME_ALERT_ANY = True  # if True, any flame detection in the window triggers an alert
# PLACEHOLDER — set this once you've measured your MQ-135's actual clean-air
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


# Matches with or without the "Gas:" field, since the Arduino sketch may not
# have the gas sensor wired in yet:
# "Humidity: 48.00%  Temperature: 22.50°C , Sound Level: 412, Light: 300, Gas: 250, Flame: 0, FlameRaw: 320"
# "Humidity: 48.00%  Temperature: 22.50°C , Sound Level: 412, Light: 300, Flame: 0, FlameRaw: 320"
LINE_PATTERN = re.compile(
    r"Humidity:\s*([\d.]+)%.*?Temperature:\s*([\d.]+).*?"
    r"Sound Level:\s*(\d+).*?Light:\s*(\d+)"
    r"(?:.*?Gas:\s*(\d+))?.*?"
    r"Flame:\s*(\d).*?FlameRaw:\s*(\d+)"
)


def parse_line(line):
    """Expects Arduino output like:
    'Humidity: 48.00%  Temperature: 22.50°C , Sound Level: 412, Light: 300, Gas: 250, Flame: 0, FlameRaw: 320'
    (Gas field is optional — will be None if not present in the line, e.g.
    before the gas sensor is physically installed.)
    """
    match = LINE_PATTERN.search(line)
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


def check_alerts(avg_temp, avg_humidity, avg_sound_db, avg_gas_raw, flame_detected_in_window):
    """Returns a list of human-readable alert messages for any threshold
    crossed by this window's averaged values. Empty list = all clear."""
    alerts = []
    if avg_temp > TEMP_ALERT_HIGH:
        alerts.append(f"Temperature high: {avg_temp:.1f}C (threshold {TEMP_ALERT_HIGH}C)")
    if avg_humidity > HUMIDITY_ALERT_HIGH:
        alerts.append(f"Humidity high: {avg_humidity:.1f}% (threshold {HUMIDITY_ALERT_HIGH}%)")
    if avg_sound_db is not None and avg_sound_db > SOUND_DB_ALERT_HIGH:
        alerts.append(f"Sound level high: {avg_sound_db:.1f}dB (threshold {SOUND_DB_ALERT_HIGH}dB)")
    if avg_gas_raw is not None and avg_gas_raw > GAS_ALERT_HIGH:
        alerts.append(f"Gas level high: {avg_gas_raw:.1f} raw (threshold {GAS_ALERT_HIGH})")
    if FLAME_ALERT_ANY and flame_detected_in_window:
        alerts.append("Flame detected during this window!")
    return alerts


def connect_serial(max_retries=5):
    """Attempt to open the serial connection, retrying with backoff on failure.
    Returns the open connection, or None if all retries are exhausted."""
    for attempt in range(1, max_retries + 1):
        try:
            ser = serial.Serial(SERIAL_PORT, BAUD_RATE, timeout=2)
            time.sleep(2)  # allow Arduino to reset after serial connection opens
            print(f"Connected to {SERIAL_PORT}")
            return ser
        except serial.SerialException as e:
            wait = min(2 ** attempt, 30)  # exponential backoff, capped at 30s
            print(f"Serial connection failed (attempt {attempt}/{max_retries}): {e}")
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
    print(f"Connecting to Arduino on {SERIAL_PORT}...")
    ser = connect_serial()
    if ser is None:
        print("Could not connect to Arduino after retries. Exiting.")
        return

    dynamodb = boto3.resource("dynamodb", region_name=AWS_REGION)
    table = dynamodb.Table(TABLE_NAME)

    print(f"Listening for sensor data — {AVERAGING_WINDOW_SECONDS}s collecting, "
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

            if is_collecting and raw_line.strip():
                parsed = parse_line(raw_line)
                if parsed is None:
                    print(f"Skipping unparseable line: {raw_line.strip()}")
                else:
                    temperature, humidity, sound_raw, light_raw, gas_raw, flame, flame_raw = parsed
                    valid, reason = is_valid_reading(
                        temperature, humidity, sound_raw, light_raw, gas_raw, flame, flame_raw
                    )
                    if valid:
                        temps.append(temperature)
                        humidities.append(humidity)
                        sounds.append(sound_raw)
                        lights.append(light_raw)
                        if gas_raw is not None:
                            gases.append(gas_raw)
                        flame_raws.append(flame_raw)
                        if flame == 1:
                            flame_detected_in_window = True
                    else:
                        rejected_count += 1
                        print(f"Rejected reading: {reason}")
            # else: idle phase — line is read (draining the buffer) but discarded

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

                        alerts = check_alerts(avg_temp, avg_humidity, avg_sound_db, avg_gas_raw, flame_detected_in_window)

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
                                # This is the hook point for a real notification —
                                # e.g. send an SNS message, email, or Slack ping here.
                        else:
                            print("Failed to store this averaging window's data — moving on.")
                    else:
                        print(f"No valid readings this window ({rejected_count} rejected) — skipping write.")

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
