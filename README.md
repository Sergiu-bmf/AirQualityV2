# AirQualityV2


The Urban Heat Island (UHI) Effect: Most weather stations are at airports or parks. Your project tracks how temperature and humidity change meter-by-meter as you move from a park to a concrete street.

Indoor vs. Outdoor Air Transition: Monitoring how air quality drops the moment you move from a ventilated street into a poorly ventilated apartment.

The Cost-Efficiency of Virtual Sensors: Using Wi-Fi Fingerprinting (software) instead of expensive GPS (hardware) for mobile environmental sensing.


Attribute,Type,Purpose
DeviceID (PK),String,"Identifies your laptop (e.g., ENV_PROBE_01)."
Timestamp (SK),String,ISO 8601 format for time-range sorting.
Temperature,Number,Raw sensor data.
Humidity,Number,Raw sensor data.
GeoHash,String,A 7-8 character string for spatial indexing.
Lat / Lng,Number,Exact coordinates (can be null if fix fails).
Loc_Source,String,"wifi, ip, or none (Critical for your ""Methodology"" section)."
Loc_Accuracy,Number,Radius in meters (Use this to discuss data reliability).


A Geohash is a string representation of coordinates (e.g., u33dc). This allows for "spatial indexing" in a NoSQL database.

The Problem: Fixed city weather stations are spaced kilometers apart, creating "Data Voids" in narrow alleys, indoor malls, or specific transit routes. The Solution: A mobile, low-cost sensor node (Arduino + Laptop) that uses "Opportunistic Geolocation" (Wi-Fi Fingerprinting) to map humidity and temperature in real-time. The Innovation: Comparing Wi-Fi vs. IP geolocation to ensure data continuity in "GPS-denied" environments (like inside university buildings).
