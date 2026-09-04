import json
import os
import re
import time
import boto3
from decimal import Decimal

dynamodb = boto3.resource("dynamodb")
TABLE_NAME = os.environ.get("TABLE_NAME", "SensorReadings")
table = dynamodb.Table(TABLE_NAME)

# Simple shared-secret check, not real auth, just keeps random internet
# traffic from hitting your function URL. Set this as an env var too.
SHARED_SECRET = os.environ.get("SHARED_SECRET", "")

# The SNS topic email alerts are delivered through. The Lambda only ever *subscribes*
# addresses to it; the pipeline is what publishes. Those are two different IAM identities
# and both need their own permission (sns:Subscribe here, sns:Publish there).
SNS_TOPIC_ARN = os.environ.get("SNS_TOPIC_ARN", "")

# Notification preferences live in the same table as the readings, but under their own
# partition key so they can never appear in a /history or /latest result.
PREFS_KEY_PREFIX = "prefs#"
PREFS_TIMESTAMP = 0

# Kept as a set rather than a boolean so adding a second channel later (an in-app push,
# say) is a one-line change here instead of a reshape of the stored item.
VALID_CHANNELS = {"email"}
EMAIL_PATTERN = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
MAX_BODY_BYTES = 2000


def decimal_to_native(obj):
    """Convert DynamoDB's Decimal types to plain int/float for JSON output."""
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
    # Basic shared-secret check via query string, e.g. ?key=yoursecret
    params = event.get("queryStringParameters") or {}
    if SHARED_SECRET and params.get("key") != SHARED_SECRET:
        return response(401, {"error": "unauthorized"})

    path = event.get("rawPath", "")
    device_id = params.get("device_id", "arduino-01")
    method = event.get("requestContext", {}).get("http", {}).get("method", "GET")

    if path.endswith("/latest"):
        return get_latest(device_id)
    elif path.endswith("/history"):
        return get_history(device_id, params)
    elif path.endswith("/prefs"):
        if method == "GET":
            return get_prefs(device_id)
        if method in ("POST", "PUT"):
            # Fail closed on writes. The read routes skip the secret check entirely when
            # SHARED_SECRET is unset, which quietly makes them public; for a route that
            # subscribes email addresses that same slip would be an open spam relay.
            if not SHARED_SECRET:
                return response(503, {"error": "SHARED_SECRET is not set; writes refused"})
            return put_prefs(device_id, event)
        return response(405, {"error": "method not allowed", "method": method})
    else:
        return response(404, {"error": "not found", "path": path})


def get_latest(device_id):
    result = table.query(
        KeyConditionExpression="device_id = :d",
        ExpressionAttributeValues={":d": device_id},
        ScanIndexForward=False,  # descending, so newest first
        Limit=1,
    )
    items = result.get("Items", [])
    if not items:
        return response(404, {"error": "no data found for this device"})
    return response(200, items[0])


""" Safety valve on the pagination loop below. At one window every 4 minutes this is
about 8 days of history, more than the widest range the app offers. """
MAX_HISTORY_ITEMS = 3000


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

    query = {
        "KeyConditionExpression": "device_id = :d AND #ts BETWEEN :s AND :e",
        "ExpressionAttributeNames": {"#ts": "timestamp"},
        "ExpressionAttributeValues": {":d": device_id, ":s": start, ":e": end},
    }

    # A single query() page caps at 1MB. Ranges of a few days exceed that, and because
    # results come back ascending by timestamp, an unpaginated read would silently drop
    # the *newest* rows, the chart would look fine while missing the last few hours.
    items = []
    truncated = False
    while True:
        result = table.query(**query)
        items.extend(result.get("Items", []))

        last_key = result.get("LastEvaluatedKey")
        if not last_key:
            break
        if len(items) >= MAX_HISTORY_ITEMS:
            truncated = True
            break
        query["ExclusiveStartKey"] = last_key

    body = {"items": items[:MAX_HISTORY_ITEMS]}
    if truncated:
        # Say so rather than pretending the window was quiet after this point.
        body["truncated"] = True
    return response(200, body)



# ---------- Notification preferences ----------


def prefs_key(device_id):
    return PREFS_KEY_PREFIX + device_id


def get_prefs(device_id):
    """Current notification settings. Absent settings are a valid state, not an error."""
    result = table.get_item(Key={"device_id": prefs_key(device_id), "timestamp": PREFS_TIMESTAMP})
    item = result.get("Item")
    if not item:
        # "configured" is what separates "nobody has ever set this" from "set, and every
        # channel is deliberately off". Both have an empty channels list, but they must
        # mean opposite things to the pipeline: the first falls back to its local
        # settings, the second must stay silent.
        return response(200, {"device_id": device_id, "configured": False, "channels": [],
                              "email": None, "email_status": "none"})
    return response(200, {
        "device_id": device_id,
        "configured": True,
        "channels": item.get("channels", []),
        "email": item.get("email"),
        "email_status": item.get("email_status", "none"),
        "updated_at": item.get("updated_at"),
    })


def put_prefs(device_id, event):
    """Replace the notification settings for a device.

    Subscribing an email to SNS only *starts* the process, AWS emails a confirmation
    link that must be clicked before anything is delivered, so the stored status stays
    "pending" until then. That is surfaced to the app rather than hidden, because an
    unconfirmed subscription looks identical to a working one right up until the fire.
    """
    body = event.get("body") or "{}"
    if len(body) > MAX_BODY_BYTES:
        return response(413, {"error": "body too large"})
    try:
        payload = json.loads(body)
    except (ValueError, TypeError):
        return response(400, {"error": "body must be JSON"})
    if not isinstance(payload, dict):
        return response(400, {"error": "body must be a JSON object"})

    channels = payload.get("channels", [])
    if not isinstance(channels, list) or not set(channels) <= VALID_CHANNELS:
        return response(400, {"error": f"channels must be a subset of {sorted(VALID_CHANNELS)}"})
    channels = sorted(set(channels))

    email = payload.get("email") or None
    if email is not None:
        if not isinstance(email, str) or len(email) > 254 or not EMAIL_PATTERN.match(email):
            return response(400, {"error": "email is not a valid address"})
    if "email" in channels and not email:
        return response(400, {"error": "email channel selected but no email given"})

    existing = table.get_item(
        Key={"device_id": prefs_key(device_id), "timestamp": PREFS_TIMESTAMP}
    ).get("Item", {})

    email_status = "none"
    subscription_arn = existing.get("sns_subscription_arn")

    if "email" in channels:
        if email == existing.get("email") and existing.get("email_status") == "confirmed":
            email_status = "confirmed"          # already working, leave it alone
        else:
            if not SNS_TOPIC_ARN:
                return response(503, {"error": "SNS_TOPIC_ARN is not configured on the Lambda"})
            try:
                sub = boto3.client("sns").subscribe(
                    TopicArn=SNS_TOPIC_ARN, Protocol="email", Endpoint=email,
                    ReturnSubscriptionArn=True,
                )
                subscription_arn = sub.get("SubscriptionArn")
                # SNS reports this literal string until the link in the email is clicked.
                email_status = ("pending" if subscription_arn == "pending confirmation"
                                else "confirmed")
            except Exception as e:                       # noqa: BLE001
                return response(502, {"error": f"could not subscribe address: {e}"})
    elif subscription_arn and subscription_arn != "pending confirmation":
        # Email switched off, actually stop the mail rather than just forgetting locally.
        try:
            boto3.client("sns").unsubscribe(SubscriptionArn=subscription_arn)
        except Exception:                                # noqa: BLE001
            pass
        subscription_arn = None

    item = {
        "device_id": prefs_key(device_id),
        "timestamp": PREFS_TIMESTAMP,
        "channels": channels,
        "email": email,
        "email_status": email_status,
        "updated_at": int(time.time()),
    }
    if subscription_arn:
        item["sns_subscription_arn"] = subscription_arn
    table.put_item(Item=item)

    return response(200, {"device_id": device_id, "configured": True, "channels": channels,
                          "email": email, "email_status": email_status})
