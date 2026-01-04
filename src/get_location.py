import subprocess
import requests
import time
from geopy.geocoders import Nominatim

# --- CONFIG ---
USER_AGENT = "air_quality_sensor_fusion_v1"
geolocator = Nominatim(user_agent=USER_AGENT)

def get_wifi_location():
    """Try to get location via Wi-Fi scanning."""
    try:
        # Force a fresh scan
        subprocess.run(["nmcli", "dev", "wifi", "rescan"], capture_output=True)
        cmd = ["nmcli", "-t", "-f", "BSSID,SIGNAL", "dev", "wifi"]
        scan = subprocess.check_output(cmd, text=True)
        
        wifi_list = []
        for line in scan.strip().split('\n'):
            parts = line.rsplit(':', 1)
            if len(parts) == 2:
                dbm = (int(parts[1]) / 2) - 100
                wifi_list.append({"macAddress": parts[0].replace('\\', ''), "signalStrength": int(dbm)})
        
        resp = requests.post("https://beacondb.net/v1/geolocate", json={"wifiAccessPoints": wifi_list}, timeout=5)
        if resp.status_code == 200:
            data = resp.json()
            return {
                "lat": data['location']['lat'],
                "lng": data['location']['lng'],
                "accuracy": data['accuracy'],
                "source": "Wi-Fi (BeaconDB)"
            }
    except:
        return None

def get_ip_location():
    """Get location via IP address."""
    try:
        resp = requests.get("http://ip-api.com/json/", timeout=5)
        if resp.status_code == 200:
            data = resp.json()
            return {
                "lat": data['lat'],
                "lng": data['lon'],
                "accuracy": 10000, # IP accuracy is generally poor (~10km)
                "source": "IP Geolocation"
            }
    except:
        return None

def main():
    print("--- Comparing Wi-Fi vs IP Location ---")
    
    # 1. Get both results
    wifi_res = get_wifi_location()
    ip_res = get_ip_location()

    # 2. Decision Logic
    best_loc = None

    if wifi_res and ip_res:
        # If we have both, choose the one with the smallest accuracy radius
        if wifi_res['accuracy'] <= ip_res['accuracy']:
            best_loc = wifi_res
            print(f"DEBUG: Wi-Fi ({wifi_res['accuracy']}m) is better than IP ({ip_res['accuracy']}m)")
        else:
            best_loc = ip_res
    elif wifi_res:
        best_loc = wifi_res
    else:
        best_loc = ip_res

    # 3. Output results
    if best_loc:
        print("\n" + "="*40)
        print(f"SELECTED SOURCE: {best_loc['source']}")
        print(f"COORDINATES:     {best_loc['lat']}, {best_loc['lng']}")
        print(f"ACCURACY:        {best_loc['accuracy']} meters")
        
        # Resolve address for the best result
        try:
            addr = geolocator.reverse(f"{best_loc['lat']}, {best_loc['lng']}", timeout=5)
            print(f"ADDRESS:         {addr.address if addr else 'N/A'}")
        except:
            print("ADDRESS:         Lookup timed out")
        print("="*40)
    else:
        print("Result: Complete failure. Check your internet connection.")

if __name__ == "__main__":
    main()
