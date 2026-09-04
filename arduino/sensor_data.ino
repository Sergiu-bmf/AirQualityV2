#include "DHT.h"
#define DHTPIN 2
#define DHTTYPE DHT11
#define SOUND_PIN A0
#define LDR_PIN A1
#define GAS_PIN A2
#define FLAME_PIN A5
#define LED_GREEN 4
#define LED_YELLOW 5
#define LED_RED 6
#define BUZZER_PIN 7
#define SD_CS_PIN 10

DHT dht(DHTPIN, DHTTYPE);

// ---- Thresholds for instant local feedback ----
// Tune these to your space; these are just starting points.
const float TEMP_HIGH = 28.0;       // C - above this counts as "warm"
const float HUMIDITY_HIGH = 50.0;   // % - above this counts as "humid"
const int SOUND_HIGH = 550;         // raw ADC ~ corresponds to ~80dB per your calibration
const int LIGHT_HIGH = 800;         // raw ADC ~ "too bright" , this is relative to your
                                     // LDR/resistor pairing, tune based on your own readings

// ---- Gas sensor (MQ-135) threshold ----
// PLACEHOLDER , MQ-135 needs a warm-up period (can take a while for the
// heating element to stabilize) before readings are meaningful, and the
// "normal" baseline varies by unit and environment. Once you have the
// sensor, let it warm up, note its resting value in clean air, and set
// this comfortably above that baseline.
const int GAS_HIGH = 400;

// ---- Flame sensor threshold ----
// Calibrated using measured values: resting ~3, flame (close range) ~700.
// Set below the midpoint to stay sensitive to flames that aren't right
// next to the sensor, while staying safely above the resting noise floor.
const int FLAME_THRESHOLD = 150;

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
  int gasLevel = analogRead(GAS_PIN);
  int flameRaw = analogRead(FLAME_PIN);

  // Adjust this comparison direction if your sensor's resting/flame values
  // turn out to be reversed once you calibrate.
  bool flameDetected = (flameRaw > FLAME_THRESHOLD);

  if (isnan(h) || isnan(t)) {
    Serial.println("Failed to read from DHT11 sensor!");
    return;
  }

  bool bad = flameDetected;  // flame is always a critical/bad condition
  bool warn = (t > TEMP_HIGH || h > HUMIDITY_HIGH || soundLevel > SOUND_HIGH ||
               gasLevel > GAS_HIGH || lightLevel > LIGHT_HIGH);

  setStatusLED(bad, warn);

  if (bad) {
    tone(BUZZER_PIN, 1000);  // no duration = plays continuously until noTone() is called
  } else {
    noTone(BUZZER_PIN);
  }

  Serial.print("Humidity: ");
  Serial.print(h);
  Serial.print("%  Temperature: ");
  Serial.print(t);
  Serial.print("°C , Sound Level: ");
  Serial.print(soundLevel);
  Serial.print(", Light: ");
  Serial.print(lightLevel);
  Serial.print(", Gas: ");
  Serial.print(gasLevel);
  Serial.print(", Flame: ");
  Serial.print(flameDetected ? 1 : 0);
  Serial.print(", FlameRaw: ");
  Serial.print(flameRaw);
  Serial.print("\n");
}
