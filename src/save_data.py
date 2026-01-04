import serial
import time
import json
import boto3
import geohash
from geopy.geocoders import Nominatim
from get_location import get_smart_location

# --- CONFIG ---
arduino_port = "/dev/ttyACM0"
dynamodb = boto3.resource('dynamodb', region_name='us-east-1') # TBD region
table = dynamodb.Table('AirQualityData') # TDB table name

def get_geodata():
    loc = get_smart_location() # Returns (lat, lng, accuracy, source)
    if loc:
        lat, lng, acc, src = loc
        ghash = geohash.encode(lat, lng, precision=7)
        return {"lat": lat, "lng": lng, "acc": acc, "src": src, "geohash": ghash}
    return None

ser = serial.Serial(arduino_port, 9600, timeout=1)

while True:
    if ser.in_waiting > 0:
        line = ser.readline().decode('utf-8').strip()
        
        # Parse Arduino Data (Humidity: 45.00% Temperature: 22.00°C)
        if "Humidity" in line:
            parts = line.replace("Humidity:", "").replace("%", "").replace("Temperature:", "").replace("°C", "").split()
            h, t = float(parts[0]), float(parts[1])
            
            # Get Location
            geo = get_geodata()
            
            # Prepare the "Research Grade" Payload
            payload = {
                "DeviceID": "MOBILE_PROBE_01",
                "Timestamp": str(int(time.time())), # Unix timestamp for easy sorting
                "ISO_Time": time.strftime("%Y-%m-%dT%H:%M:%SZ"),
                "SensorData": {"temp": t, "humidity": h},
                "Geo": geo if geo else {"status": "NO_FIX"}
            }

            # Upload to DynamoDB
            table.put_item(Item=payload)
            print(f"Uploaded: {t}°C at {geo['geohash'] if geo else 'Unknown'}")
