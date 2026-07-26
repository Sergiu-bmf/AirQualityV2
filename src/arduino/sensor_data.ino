#include "DHT.h"

#define DHTPIN 2
#define DHTTYPE DHT11
#define SOUND_PIN A0
#define LDR_PIN A1
#define FLAME_PIN A5
#define LED_GREEN 4
#define LED_YELLOW 5
#define LED_RED 6
#define BUZZER_PIN 7

DHT dht(DHTPIN, DHTTYPE);

// ---- Thresholds for instant local feedback ----
// Tune these to your space; these are just starting points.
const float TEMP_HIGH = 28.0;       // C - above this counts as "warm"
const float HUMIDITY_HIGH = 70.0;   // % - above this counts as "humid"
const int SOUND_HIGH = 550;         // raw ADC ~ corresponds to ~80dB per your calibration

// ---- Flame sensor threshold ----
// This is a PLACEHOLDER - you need to calibrate it yourself:
// 1. Run the flame_sensor_test.ino sketch with the sensor wired as-is.
// 2. Note the resting value (no flame) and the value with a flame nearby.
// 3. Set FLAME_THRESHOLD roughly halfway between them, on whichever side
//    means "flame present" for your specific sensor's wiring orientation.
const int FLAME_THRESHOLD = 500;

void setup() {
  Serial.begin(9600);
  dht.begin();

  pinMode(LED_GREEN, OUTPUT);
  pinMode(LED_YELLOW, OUTPUT);
  pinMode(LED_RED, OUTPUT);
  pinMode(BUZZER_PIN, OUTPUT);
}

void setStatusLED(bool bad, bool warn) {
  digitalWrite(LED_RED, bad ? HIGH : LOW);
  digitalWrite(LED_YELLOW, (!bad && warn) ? HIGH : LOW);
  digitalWrite(LED_GREEN, (!bad && !warn) ? HIGH : LOW);
}

void loop() {
  delay(2000);

  float h = dht.readHumidity();
  float t = dht.readTemperature();
  int soundLevel = analogRead(SOUND_PIN);
  int lightLevel = analogRead(LDR_PIN);
  int flameRaw = analogRead(FLAME_PIN);

  // Adjust this comparison direction if your sensor's resting/flame values
  // turn out to be reversed once you calibrate.
  bool flameDetected = (flameRaw > FLAME_THRESHOLD);

  if (isnan(h) || isnan(t)) {
    Serial.println("Failed to read from DHT11 sensor!");
    return;
  }

  bool bad = flameDetected;  // flame is always a critical/bad condition
  bool warn = (t > TEMP_HIGH || h > HUMIDITY_HIGH || soundLevel > SOUND_HIGH);

  setStatusLED(bad, warn);

  if (bad) {
    tone(BUZZER_PIN, 1000, 300);  // brief alert tone, non-blocking
  }

  Serial.print("Humidity: ");
  Serial.print(h);
  Serial.print("%  Temperature: ");
  Serial.print(t);
  Serial.print("°C , Sound Level: ");
  Serial.print(soundLevel);
  Serial.print(", Light: ");
  Serial.print(lightLevel);
  Serial.print(", Flame: ");
  Serial.print(flameDetected ? 1 : 0);
  Serial.print(", FlameRaw: ");
  Serial.print(flameRaw);
  Serial.print("\n");
}
