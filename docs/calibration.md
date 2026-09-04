# Calibration

Four of the six sensors report something other than raw ADC. Each conversion has a
different amount of evidence behind it, and this page is explicit about which.

| Sensor | Reported as | Confidence |
|---|---|---|
| DHT11 | °C and % directly | Datasheet, ±2 °C / ±5 % |
| Sound | dB | **Measured**, 4 points, fitted curve |
| Light | lux | **Assumed**, unverified curve for an unidentified part |
| Gas | raw ADC | **Uncalibrated**, conversion built but dormant |
| Flame | % of alarm threshold | Measured, but the resting value drifts |

## Sound (HW-484) → decibels

Four points measured against a phone dB meter alongside the Arduino:

| Raw ADC | Measured dB |
|---|---|
| ~170–230 | ~40 |
| ~390 | ~60 |
| ~500 | ~70 |
| ~660 | ~80 |

Fitted logarithmic curve, R² ≈ 0.996:

```
dB = 33.39 * ln(raw) - 137.59
```

Implemented as `raw_to_db()` in the pipeline. Valid only inside the measured ~40–80 dB
range and unreliable outside it. Calibrated against a phone app, not a lab reference, good
for trends, not for certified numbers.

!!! note "dB is computed from the averaged raw value"

    Not by averaging individual dB readings. Decibels are logarithmic, so averaging them
    directly skews the result.

## Light (LDR)

Converted to **approximate lux**, in the app at read time, the stored value is still raw
ADC.

The divider maths is exact, given the wiring in [Hardware](hardware.md#wiring):

```
R_LDR = 10kΩ × (1023 - raw) / raw
```

Turning resistance into lux needs the LDR's response curve, and this is a bare unmarked kit
component. The code assumes a **GL5528**:

```
lux = 10 × (R10 / R_LDR) ^ (1 / γ)      R10 = 10 kΩ at 10 lux,  γ = 0.6
```

!!! warning "Treat the absolute scale as unverified"

    Observed indoor readings land at 0.0–0.2 lux, far darker than a lit room actually is.
    The assumed curve is very likely under-reading by a large factor. *Relative* change over
    time is still meaningful.

    To fix it, measure one point with a phone lux meter and adjust `LDR_R10_OHMS` in
    `SensorConversions.kt`. Lux scales as `(R10 / R)^(1/γ)`, so raising that constant raises
    every reading.

Converting at read time rather than write time is deliberate: changing that constant
re-scales the entire stored history on next launch, instead of splicing a differently-scaled
tail onto rows already written under the old value.

## Flame sensor

- Initial wiring per the standard leg convention gave a flat, unusable 0→10 swing.
  **Swapping the legs** fixed it: resting ~3, flame at close range ~700.
- Threshold set to **150**, below the 350 midpoint, to stay sensitive to flames that are
  not directly adjacent, while staying safely above the resting noise floor.
- Direction: `flameRaw > FLAME_THRESHOLD`, the value *increases* with flame present.

!!! warning "The resting value has drifted"

    Later observations show it anywhere from ~8 to ~56 raw depending on ambient infrared ,
    daylight, warm lamps, against the ~3 originally recorded. With the threshold at 150
    that is still workable headroom, but it is worth re-checking before trusting the
    alerting overnight: a false trip now sends an email.

The app charts this as **percent of the detection threshold**, so 100 % is the alarm point
by construction and the remaining headroom is readable at a glance.

## Gas (MQ-135)

Delivered and wired to `A2`. **Still uncalibrated.** Thresholds remain the placeholder 400
in both the sketch and the pipeline, and observed readings sit around 320–380 raw ,
uncomfortably close to that line.

An Rs/R₀ conversion is implemented and dormant. Set `GAS_CLEAN_AIR_RAW` in
`SensorConversions.kt` to the raw value the sensor settles at in clean air after warm-up,
and the app's gas metric switches itself from raw ADC to a ratio, unit, number format and
reference line together. Until then it deliberately keeps showing raw rather than inventing
a ratio from a guessed baseline.

```
Rs/R₀ = [(1023 - raw) / raw] ÷ [(1023 - baseline) / baseline]
```

1.0 is clean air; lower means more of whatever the sensor responds to.

!!! tip "The load resistor cancels out"

    Rs/R₀ is a ratio of two resistances measured through the same load resistor, so it
    doesn't matter whether the module carries 1 kΩ or 10 kΩ. The clean-air baseline is the
    only number you need to measure.

!!! danger "Why not ppm"

    The MQ-135 responds to CO₂, ammonia, benzene and smoke together and cannot distinguish
    them. Any single ppm figure is fiction without calibration against a reference gas, the
    widely-copied `ppm = 116.6 × (Rs/R₀)^-2.77` formula included.

## Thresholds are duplicated in three places

There is no shared source of truth, and nothing enforces that these match:

| Where | Constants | Role |
|---|---|---|
| `arduino/sensor_data.ino` | `TEMP_HIGH`, `HUMIDITY_HIGH`, `SOUND_HIGH`, `LIGHT_HIGH`, `GAS_HIGH` | Instant LED/buzzer feedback, per reading |
| `pipeline/sensor_pipeline.py` | `TEMP_ALERT_HIGH`, `HUMIDITY_ALERT_HIGH`, `SOUND_DB_ALERT_HIGH`, `LIGHT_ALERT_HIGH`, `GAS_ALERT_HIGH` | The stored `alerts` and `status`, per window |
| `…/ui/components/AlertThresholds.kt` | `TEMPERATURE_C`, `HUMIDITY_PERCENT`, `SOUND_DB`, `LIGHT_RAW`, `GAS_RAW`, `FLAME_RAW_DETECT` | Chart reference lines, display only |

Change one and the others silently desync, the LED can disagree with the stored status, or
a chart's reference line with the traffic light above it.

Note the Arduino compares **raw ADC** sound (`SOUND_HIGH = 550`) while the pipeline and app
compare **dB** (`75.0`), so those two are related by `raw_to_db()` rather than being equal
numbers.

!!! note "Validation is a different thing from alerting"

    `is_valid_reading()` rejects physically impossible sensor glitches before a reading
    enters an average. `check_alerts()` runs afterwards on the averaged values and is about
    real-world thresholds. The split is intentional, see [Pipeline](pipeline.md).
