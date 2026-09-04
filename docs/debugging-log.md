# Debugging log

Every problem hit and resolved, in rough chronological order. Kept because several of these
look like something else entirely on first inspection, and re-deriving them is expensive.

## Environment and tooling

**`pip install` → externally-managed-environment.**
Use `--break-system-packages`, or a virtual environment.

**AWS CLI install method.**
Chose AWS's official installer script over `apt` (version lag) or `pip` (not recommended by
AWS for the CLI specifically).

**`snap install android-studio` stuck** after being cancelled mid-install
(`snap "android-studio" has "install-snap" change in progress`).
`snap changes android-studio` → find the stuck ID → `sudo snap abort <ID>` → retry. When that
wasn't enough, `sudo systemctl restart snapd`. Full reset path if needed: remove, restart
snapd, reinstall.

**`adb: command not found`.**
`adb` lives in `~/Android/Sdk/platform-tools/`, not on `PATH`. Added to `~/.bashrc`.

**`adb devices` → `no permissions (missing udev rules?)`.**
Tried the usual: `plugdev` group, community udev rules, reload udev, restart the adb server,
log out and in. None of it was the cause.

!!! danger "The real cause was the USB dock"

    The phone was connected through a docking station. Docks can fail to pass through full
    USB data/ADB functionality even when charging and file transfer appear to work. Connecting
    directly to a laptop port resolved it immediately.

## Hardware

**Flame sensor: floating, noisy readings**, a smooth 0→1023→0 ramp, the classic floating-pin
signature. It persisted with the sensor physically removed, *and* with the pin wired directly
to GND on a different pin.

!!! danger "The breadboard's ground rail is split"

    The power and ground rails are two physically disconnected halves, and the Arduino's GND
    and the sensor's GND tap were on opposite, unbridged halves. Confirmed with a direct
    pin-to-pin jumper test bypassing the breadboard entirely, which read a clean, stable `0`.
    Fix: bridge both halves with a jumper wire.

**Flame sensor: still a weak 0→10 swing after the grounding fix.**
The bare 2-leg component was wired in reverse polarity. Swapping the legs gave resting ~3 and
~700 with a flame close, usable. See [Calibration](calibration.md#flame-sensor).

## AWS

**`TypeError: Float types are not supported. Use Decimal types instead.`**
boto3's DynamoDB resource layer rejects native Python `float`. Fixed with a recursive
`floats_to_decimal()` using `Decimal(str(value))`, via `str()` first, to avoid floating-point
artefacts.

**`ResourceNotFoundException` on `PutItem`.**
The table existed in a different region than boto3 defaulted to. Fixed by setting
`region_name="eu-central-1"` explicitly and `aws configure set region eu-central-1`.

**`EndpointConnectionError: could not connect to dynamodb.us-central-1.amazonaws.com`.**
`us-central-1` is not a valid AWS region at all, a typo for `eu-central-1`, hardcoded in the
script and overriding the correct `~/.aws/config` default.

**Lambda Function URL returning non-JSON** (`JSON.parse: unexpected character`) in a browser.
Diagnosed by switching to `curl -i`, which shows the real status code and headers that a
browser tab hides, plus CloudWatch Logs.

**Lambda 502, cause 1: handler misconfiguration.**
AWS pre-fills `lambda_function.lambda_handler`, which doesn't match this code's function name
`handler`. Fixed under Configuration → Runtime settings → Edit.

**Lambda 502, cause 2: `AccessDeniedException` on `dynamodb:Query`.**
The auto-generated execution role didn't actually have the read policy attached, despite an
earlier attempt that appeared to succeed. Re-attached directly on that role.

!!! warning "The confusion that cost the most time here"

    The Lambda's auto-created execution **role** was briefly conflated with the separate,
    pre-existing IAM **user** used by the pipeline. Two entirely separate identities with
    independent permissions; fixing one does nothing for the other. This recurred later when
    adding SNS, see [Cloud](cloud.md#three-identities-easy-to-conflate).

**`/history` → `"start and end query params required"`.**
Not a bug: both parameters are mandatory by design. Use `date +%s` and
`date -d '24 hours ago' +%s`.

**`POST /prefs` → 502 with no useful client-side error.**
CloudWatch was unavailable to the pipeline's IAM user, so the cause was found by elimination:
`GET /prefs` succeeded (so `GetItem` worked), the request carried no email (so SNS was never
called), leaving `put_item` as the only candidate. The Lambda's role was missing
`dynamodb:PutItem`; it had only ever needed read access before the `/prefs` route existed.

## Serial

**Truncated lines** appearing as `Skipping unparseable line: 76, Gas: 462, Flame: 0, FlameRaw: 51`.
That is the *tail* of a line whose head was consumed by a previous read. Reproduced on a
pseudo-terminal: `pyserial`'s `readline()` returns whatever it has when the timeout expires,
and a 2 s timeout against a ~2.03 s Arduino loop period sits right on the boundary. Fixed by
raising the timeout to 5 s and, on connect, flushing the buffer and discarding one line so the
first parsed line is guaranteed to start at `Humidity:`.

**A merged line parsed into a blended reading.**
Found while verifying the above. With permissive `.*?` separators, a head glued to the next
full line matched, taking temperature from one reading and light and gas from another, and
the result passed validation and would have been stored.

!!! warning "Anchoring the start alone did not fix it"

    The backtracking happens at `Light:`, not at the start. The fix is anchoring **both** ends
    *and* replacing the `.*?` gaps with the sketch's literal `,` separators. The strict
    separators are the load-bearing part.

**`could not open port /dev/ttyACM0: No such file or directory` (`Errno 2`).**
The board was connected and working; it had simply enumerated as `/dev/ttyACM1`. The trailing
number is not stable; the kernel hands out the next free one, so a replug or a reset while the
old node is held moves it. Fixed by auto-detecting the port by USB vendor ID on every connect
attempt. `Errno 2` means the path doesn't exist; `Errno 13` would mean permissions, which is a
completely different fix.

## Alerting

**Emails arrived but the app showed nothing.**
Alerting had been decoupled from the collect/idle cycle and ran continuously, while storage
still sampled only 60 s in every 240 s, so roughly 75 % of brief flames were emailed and
never written to any row. Fixed by carrying an idle-phase detection into the next stored row.

**Redeploying the Lambda silently disabled alerting.**
Before the `/prefs` route existed the endpoint 404'd, the pipeline logged "keeping previous
settings" and fell back to the local `.env`. Once deployed, it returned `channels: []`, which
the pipeline read as *"the user turned everything off"* and went silent.

!!! danger "Unconfigured and switched-off must be different states"

    Both are an empty channel list. Fixed with a `configured` flag that is false until
    something has actually been saved. This is the failure mode you would discover during a
    fire, which is the worst possible time.
