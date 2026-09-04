import json
import os
import boto3
from decimal import Decimal

dynamodb = boto3.resource("dynamodb")
TABLE_NAME = os.environ.get("TABLE_NAME", "SensorReadings")
table = dynamodb.Table(TABLE_NAME)

# Simple shared-secret check — not real auth, just keeps random internet
# traffic from hitting your function URL. Set this as an env var too.
SHARED_SECRET = os.environ.get("SHARED_SECRET", "")


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
        ScanIndexForward=False,  # descending, so newest first
        Limit=1,
    )
    items = result.get("Items", [])
    if not items:
        return response(404, {"error": "no data found for this device"})
    return response(200, items[0])


""" Safety valve on the pagination loop below. At one window every 4 minutes this is
about 8 days of history — more than the widest range the app offers. """
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
    # the *newest* rows — the chart would look fine while missing the last few hours.
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

