# Known limitations

Documented rather than hidden. These are the things that would otherwise look like bugs, or
quietly not work at the moment you needed them.

## The alerting chain

!!! danger "Alerts exist only while the pipeline is running"

    The laptop is what watches the flame sensor. Close the lid and the Arduino's own buzzer
    still fires , that is the real safety mechanism , but no email does. Nothing in the app
    indicates that the pipeline has stopped, beyond the coverage figure gradually falling.

**A flame between sampling windows is timestamped late.** Alerting runs continuously, but
storage samples 60 s in every 240 s. A detection during the idle stretch is carried into the
next stored row, up to ~3 minutes after it happened. Recording it against a window it wasn't
sampled in is the honest trade against inventing an extra row and breaking the app's
fixed-cadence coverage maths.

**Alert emails land in spam.** SNS sends from a shared AWS address with no domain alignment;
the confirmation was flagged as suspicious. A mail rule for `sns.amazonaws.com` is effectively
required for this to work as an alarm , see [Cloud](cloud.md#sns).

**A validation floor can silence a whole window.** `is_valid_reading()` rejects the entire
reading if any field is out of range, and `HUMIDITY_MIN = 20` sits above what a dry heated
room reads in winter. Flame alerting is deliberately exempt, but storage is not: every reading
rejected means no row at all, which is indistinguishable from the laptop being off.

## Security

!!! danger "There is no real authentication"

    The Lambda Function URL is `authType: NONE`, guarded only by a shared secret in a query
    parameter , which appears in browser history, server logs and shell history. Worse, the
    check is **skipped entirely** when `SHARED_SECRET` is unset, so a missing environment
    variable silently makes reads fully public.

    The write route fails closed instead, returning 503 when the secret is unset.

Anyone with the secret can read all sensor history and change the notification address.

## Measurement accuracy

**Lux is an assumed curve.** The divider maths is exact, but the LDR is an unmarked kit
component and the conversion assumes a GL5528. Observed indoor readings of 0.0–0.2 lux are
far too dark for a lit room, so the scale is likely off by a large factor. Relative change is
still meaningful. One measurement with a phone lux meter would fix it , see
[Calibration](calibration.md#light-ldr).

**Gas is still raw ADC.** The Rs/R₀ conversion is implemented and dormant, waiting on the
MQ-135's clean-air baseline after warm-up. Its alert threshold is still the placeholder 400,
and readings sit at 320–380 , close enough that a small drift would produce constant alerts.

**Sound dB is phone-calibrated.** Four points against a phone app, valid only across ~40–80 dB.
Good for trends, not for any claim about absolute loudness.

**The flame sensor's resting value drifts** with ambient infrared , observed between ~8 and
~56 raw against a threshold of 150. Still workable headroom, but worth re-checking before
trusting overnight alerting, since a false trip now sends email.

**Temperature and humidity are DHT11 grade** , ±2 °C and ±5 %, and the sensor is rated only
0–50 °C and 20–90 % RH.

## Structure

**Thresholds are duplicated by hand in three places** with nothing enforcing that they match.
The pipeline's window cadence is mirrored by hand in a fourth. See
[Calibration](calibration.md#thresholds-are-duplicated-in-three-places).

**No RTC on the Arduino.** Timestamps come from the laptop's clock. If the laptop and phone
clocks disagree by more than a few minutes, fresh rows can land outside the range the app
queries and simply not appear.

**Single device.** `device_id` defaults to `arduino-01` and is hard-coded in three places ,
the pipeline, the Lambda's parameter default, and the app's Gradle default. Renaming it in
only one produces an empty app with no error, because `/latest` 404s and the app treats that
as the normal "no rows yet" state.

**No infrastructure-as-code.** Every AWS resource was created by hand. `aws/lambda-role-policy.json`
is the only record of what the Lambda's role needs.

**No Python tests.** The `tests/` directory is empty; the only automated tests are the JVM
unit tests in the Android app.
