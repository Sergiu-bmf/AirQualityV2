# Setup

Four independent pieces. The Arduino works alone; each layer above it adds capability.

## 1. Arduino

Flash `arduino/sensor_data.ino` with the Arduino IDE or CLI. It needs the **DHT** library.

From this point the LEDs and buzzer work with nothing else attached, no laptop, no network.
Everything below is about *recording* and *notifying*.

## 2. Pipeline

```bash
pip install -r requirements.txt pyserial --break-system-packages   # or use a venv
aws configure                                                     # region eu-central-1
./run_pipeline.sh
```

!!! note "`pyserial` is missing from `requirements.txt`"

    `sensor_pipeline.py` needs it for `import serial`, so install it explicitly as above.
    `requests`, `geopy` and `python-geohash` in that file are leftovers from an abandoned
    geolocation direction; only `requests` and `boto3` are actually used.

The serial port is auto-detected, so no configuration is needed even if the board comes back
as `ttyACM1` after a replug.

For notifications, create `pipeline/.env` (gitignored via `*.env`):

```bash
AIRQ_LAMBDA_URL=https://<id>.lambda-url.eu-central-1.on.aws
AIRQ_LAMBDA_KEY=<the Lambda's SHARED_SECRET>
AIRQ_SNS_TOPIC_ARN=arn:aws:sns:eu-central-1:<account>:AirQualityAlerts
```

All optional. On startup the pipeline prints which channels are live, so a misconfiguration
shows up immediately rather than during a fire.

## 3. AWS

**DynamoDB**, table `SensorReadings`, partition key `device_id` (String), sort key
`timestamp` (Number), on-demand billing.

**Lambda**, function `sensor-api`, Python 3.12, handler `lambda_function.handler` (*not* the
pre-filled default), environment `TABLE_NAME`, `SHARED_SECRET`, `SNS_TOPIC_ARN`. Enable a
Function URL with auth type `NONE`.

**SNS**, create the topic:

```bash
aws sns create-topic --name AirQualityAlerts --region eu-central-1
```

**IAM**, two grants on two different identities. See [Cloud](cloud.md#three-identities-easy-to-conflate)
for why neither covers the other.

On the pipeline's IAM user:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": "sns:Publish",
    "Resource": "arn:aws:sns:eu-central-1:<account>:AirQualityAlerts"
  }]
}
```

On the Lambda's execution role (this is `aws/lambda-role-policy.json` in the repo):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["dynamodb:GetItem", "dynamodb:PutItem"],
      "Resource": "arn:aws:dynamodb:eu-central-1:<account>:table/SensorReadings"
    },
    {
      "Effect": "Allow",
      "Action": ["sns:Subscribe", "sns:Unsubscribe"],
      "Resource": "arn:aws:sns:eu-central-1:<account>:AirQualityAlerts"
    }
  ]
}
```

!!! warning "`PutItem` is easy to miss"

    The Lambda only ever *read* from DynamoDB until the `/prefs` route was added. Without
    `PutItem`, saving from the app fails with a 502; without `GetItem`, the pipeline can never
    read what you saved.

IAM changes take effect within seconds, no redeploy needed.

## 4. Android app

Put three keys in `android-app/local.properties` (gitignored):

```properties
sensor.api.baseUrl=https://<id>.lambda-url.eu-central-1.on.aws/
sensor.api.key=<the Lambda's SHARED_SECRET>
sensor.deviceId=arduino-01
```

```bash
cd android-app
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

!!! note "Gradle's toolchain is JDK 21"

    Pinned in `gradle/gradle-daemon-jvm.properties`. Gradle auto-provisions it into
    `~/.gradle/jdks`, so a cold-cache first build needs network and `--offline` will fail.
    Don't "fix" that by pointing `JAVA_HOME` at an older JDK.

## Verifying it works

Against a deployed Lambda:

```bash
BASE=https://<id>.lambda-url.eu-central-1.on.aws
curl "$BASE/latest?key=$SECRET&device_id=arduino-01"
curl "$BASE/history?key=$SECRET&device_id=arduino-01&start=$(date -d '6 hours ago' +%s)&end=$(date +%s)"
```

Reading the newest stored row without going through the API:

```bash
aws dynamodb query --region eu-central-1 --table-name SensorReadings \
  --key-condition-expression 'device_id = :d' \
  --expression-attribute-values '{":d":{"S":"arduino-01"}}' \
  --no-scan-index-forward --max-items 1
```

Then hold a flame near the sensor. The buzzer should fire instantly, the terminal should print
`!!! FLAME DETECTED !!!` followed by `Notified via email.`, and the email should arrive within
seconds, **check your spam folder**.

## Building this documentation

Zensical is a command-line tool, not something the project imports, so it does not belong
in system Python. On Ubuntu, `pip install zensical` fails with
`externally-managed-environment` by design.

```bash
python3 -m venv .venv          # once; .venv/ is gitignored
.venv/bin/pip install zensical

.venv/bin/zensical serve       # live preview on http://localhost:8000
.venv/bin/zensical build       # writes to site/
```

!!! tip "Or install it globally with pipx"

    `sudo apt install pipx && pipx install zensical` puts `zensical` on your `PATH`, isolated
    in its own environment. That is the tidier option for a tool you use across projects.

    Do **not** reach for `--break-system-packages` here. That is the right call for the
    pipeline's dependencies, which your script has to import, but wrong for a standalone
    tool; it writes into the Python that `apt` manages, for no benefit.

Pushing to `master` deploys the site via `.github/workflows/docs.yml`, which installs
Zensical in a clean runner where none of this applies. Set **Settings → Pages → Source** to
**GitHub Actions**.
