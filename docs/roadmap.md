# Status and roadmap

## Working

- **Arduino** reads DHT11, sound, LDR, MQ-135 and flame continuously, driving the LEDs and
  buzzer with no computer attached.
- **Pipeline** runs the collect(60 s)/idle(180 s) cycle, validates, averages and writes to
  DynamoDB with retry and backoff, auto-detecting the serial port.
- **Flame alerting** end to end: detection in ~2 s, de-duplicated, delivered by email through
  SNS, with the detection also recorded in the stored history.
- **Lambda API** serving `/latest`, `/history` and `/prefs`, with pagination on history.
- **Android app** showing live status, a status ribbon over time, data coverage, alert history
  and a chart per metric, plus an Alerts sheet that writes settings the pipeline reads back.

## Not done

- **Gas calibration.** The MQ-135 is wired and reporting, but its clean-air baseline has never
  been measured, so the threshold is still a placeholder and the Rs/R₀ conversion stays
  dormant. This is the single highest-value outstanding measurement.
- **Light calibration.** One reading against a phone lux meter would fix a scale that is
  currently far too dark.
- **In-app push notifications.** Alerts are email only. FCM would put them in this app rather
  than an inbox, but needs a Firebase project, a messaging service, the Android 13+
  notification permission, token storage and a sender. The detection and dispatch layer is
  channel-agnostic, so that work would be additive.

    !!! note "An ntfy channel was built and removed"

        It worked , real push notifications in seconds , but arrived in the ntfy app rather
        than this one, and was dropped at the owner's request. `notify()` still fans out over
        a list and the Lambda still stores `channels` as a set, so adding a channel back is a
        one-line change rather than a reshape.

- **Alerting without the laptop.** DynamoDB Streams triggering a Lambda would make alerts
  survive the pipeline being stopped, at the cost of returning to window-cadence latency.
- **Real authentication** on the Lambda, replacing the shared-secret query parameter.
- **Python tests.** `tests/` is empty.
- **Multiple devices.** `device_id` is effectively fixed at `arduino-01`.
- **Standalone Arduino operation** , SD logging was built and then removed; WiFi and an RTC
  remain researched but unpurchased. See [Hardware](hardware.md#standalone-operation).

## Rough priority

1. **Measure the MQ-135's clean-air baseline** after a proper warm-up, set
   `GAS_CLEAN_AIR_RAW`, and set the alert thresholds off the measured value rather than the
   placeholder.
2. **Re-check the flame threshold** against the drifted resting value before relying on
   overnight alerting.
3. **One lux calibration point** to make the light scale mean something.
4. **A mail rule for `sns.amazonaws.com`**, without which the alerting is unreliable in
   practice regardless of how well the code works.
5. **FCM**, if in-app notifications are worth the setup.
6. **Streams-based alerting**, if alerts should survive the laptop being closed.
