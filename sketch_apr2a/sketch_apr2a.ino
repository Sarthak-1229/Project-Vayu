#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include "DHT.h"

// --- Pin Definitions ---
#define DHTPIN       4
#define DHTTYPE      DHT22
#define MQ135_PIN    34
#define DUST_ANA_PIN 35
#define DUST_LED_PIN 2
#define ANION_PIN    27

// --- BLE UUIDs ---
#define SERVICE_UUID        "0000180A-0000-1000-8000-00805F9B34FB"
#define CHAR_TX_UUID        "00002A57-0000-1000-8000-00805F9B34FB"
#define CHAR_RX_UUID        "00002A58-0000-1000-8000-00805F9B34FB"

DHT dht(DHTPIN, DHTTYPE);
BLECharacteristic* pTxChar;
bool deviceConnected = false;
bool anionManual = false;
bool anionStatus = false;
bool lastAnionStatus = false;  // << track last state to detect changes

float temperature = 0;
float humidity    = 0;
int   mq135Raw    = 0;
float pm25        = 0;

unsigned long lastReadTime = 0;
const unsigned long readInterval = 2500;

// --- Helper: Print Anion Status clearly ---
void printAnionStatus(String source) {
  Serial.println("─────────────────────────────────");
  Serial.print  ("  [D27 RELAY] ");
  if (anionStatus) {
    Serial.println("★ ANION ON  ★  (Pin D27 = HIGH)");
  } else {
    Serial.println("○ ANION OFF ○  (Pin D27 = LOW)");
  }
  Serial.print  ("  Triggered by: ");
  Serial.println(source);
  Serial.print  ("  Mode: ");
  Serial.println(anionManual ? "MANUAL" : "AUTO");
  Serial.println("─────────────────────────────────");
}

// --- BLE Server Callbacks ---
class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) {
    deviceConnected = true;
    Serial.println("=================================");
    Serial.println("  APP CONNECTED via BLE");
    Serial.println("=================================");
  }
  void onDisconnect(BLEServer* pServer) {
    deviceConnected = false;
    Serial.println("=================================");
    Serial.println("  APP DISCONNECTED");
    Serial.println("=================================");
    pServer->getAdvertising()->start();
  }
};

// --- BLE Write Callback (App → ESP32) ---
class RxCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* pChar) {
    String val = pChar->getValue().c_str();
    val.trim();

    // ✅ Show raw signal received
    Serial.println("=================================");
    Serial.print  ("  [SIGNAL RECEIVED on RX] → \"");
    Serial.print  (val);
    Serial.println("\"");
    Serial.println("=================================");

    if (val == "1") {
      digitalWrite(ANION_PIN, HIGH);
      anionStatus = true;
      anionManual = true;
      printAnionStatus("APP COMMAND (Manual ON)");
    } else if (val == "0") {
      digitalWrite(ANION_PIN, LOW);
      anionStatus = false;
      anionManual = false;
      printAnionStatus("APP COMMAND (Manual OFF / Auto resumed)");
    } else {
      Serial.print("  [WARNING] Unknown command: ");
      Serial.println(val);
    }
  }
};

// --- Sensor Reading ---
void readSensors() {
  long sum = 0;
  for (int i = 0; i < 10; i++) {
    sum += analogRead(MQ135_PIN);
    delay(5);
  }
  mq135Raw = sum / 10;

  float dustTotal = 0;
  for (int i = 0; i < 20; i++) {
    digitalWrite(DUST_LED_PIN, LOW);
    delayMicroseconds(280);
    dustTotal += analogRead(DUST_ANA_PIN);
    delayMicroseconds(40);
    digitalWrite(DUST_LED_PIN, HIGH);
    delayMicroseconds(9680);
  }
  float dustVoltage = (dustTotal / 20.0) * (3.3 / 4095.0);
  pm25 = max(0.0f, (0.17f * dustVoltage - 0.1f) * 1000.0f);

  float tempSum = 0, humSum = 0;
  int valid = 0;
  for (int i = 0; i < 5; i++) {
    float h = dht.readHumidity();
    float t = dht.readTemperature();
    if (!isnan(h) && !isnan(t)) {
      tempSum += t;
      humSum  += h;
      valid++;
    }
    delay(100);
  }
  if (valid > 0) {
    temperature = tempSum / valid;
    humidity    = humSum  / valid;
  }
}

// --- AQI Calculation ---
int aqiFromPM25(float pm) {
  if (pm <  0)     return 0;
  if (pm <= 12.0)  return map(pm, 0,     12.0,  0,   50);
  if (pm <= 35.4)  return map(pm, 12.1,  35.4,  51,  100);
  if (pm <= 55.4)  return map(pm, 35.5,  55.4,  101, 150);
  if (pm <= 150.4) return map(pm, 55.5,  150.4, 151, 200);
  if (pm <= 250.4) return map(pm, 150.5, 250.4, 201, 300);
  return map(pm, 250.5, 500.4, 301, 500);
}

int aqiFromGas(int raw) {
  if (raw < 400)  return 50;
  if (raw < 800)  return map(raw, 400,  800,  51,  100);
  if (raw < 1500) return map(raw, 800,  1500, 101, 150);
  return map(raw, 1500, 4095, 151, 300);
}

// --- Setup ---
void setup() {
  Serial.begin(115200);
  dht.begin();

  pinMode(ANION_PIN,    OUTPUT); digitalWrite(ANION_PIN,    LOW);
  pinMode(DUST_LED_PIN, OUTPUT); digitalWrite(DUST_LED_PIN, HIGH);

  Serial.println("=================================");
  Serial.println("  PROJECT VAYU - STARTING UP");
  Serial.println("  Anion Pin: D27");
  Serial.println("  Initial State: ANION OFF");
  Serial.println("=================================");

  BLEDevice::init("Project Vayu");
  BLEDevice::setMTU(185);

  BLEServer* pServer = BLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());

  BLEService* pService = pServer->createService(BLEUUID(SERVICE_UUID), 20);

  pTxChar = pService->createCharacteristic(
              CHAR_TX_UUID,
              BLECharacteristic::PROPERTY_NOTIFY
            );
  pTxChar->addDescriptor(new BLE2902());
  pTxChar->setValue("");

  BLECharacteristic* pRxChar = pService->createCharacteristic(
                                 CHAR_RX_UUID,
                                 BLECharacteristic::PROPERTY_WRITE |
                                 BLECharacteristic::PROPERTY_WRITE_NR
                               );
  pRxChar->setCallbacks(new RxCallbacks());

  pService->start();
  pServer->getAdvertising()->start();
  Serial.println("  Waiting for app to connect...");
  Serial.println("=================================");
}

// --- Loop ---
void loop() {
  if (deviceConnected && millis() - lastReadTime > readInterval) {
    lastReadTime = millis();

    readSensors();

    int finalAQI = max(aqiFromPM25(pm25), aqiFromGas(mq135Raw));

    // Auto anion control
    if (!anionManual) {
      if (finalAQI > 150) {
        digitalWrite(ANION_PIN, HIGH);
        anionStatus = true;
      } else {
        digitalWrite(ANION_PIN, LOW);
        anionStatus = false;
      }

      // Print only when state changes in auto mode
      if (anionStatus != lastAnionStatus) {
        printAnionStatus("AUTO (AQI=" + String(finalAQI) + ")");
        lastAnionStatus = anionStatus;
      }
    }

    // Always print a compact status line every cycle
    Serial.print("[STATUS] AQI:");
    Serial.print(finalAQI);
    Serial.print("  Temp:");
    Serial.print(temperature, 1);
    Serial.print("C  Hum:");
    Serial.print(humidity, 0);
    Serial.print("%  D27:");
    Serial.print(anionStatus ? "HIGH(ON)" : "LOW(OFF)");
    Serial.print("  Mode:");
    Serial.println(anionManual ? "MANUAL" : "AUTO");

    String data = String(temperature, 1) + "," +
                  String(humidity, 0)    + "," +
                  String(finalAQI)       + "," +
                  String(anionStatus ? 1 : 0);

    pTxChar->setValue(data.c_str());
    pTxChar->notify();
  }
}