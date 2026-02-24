#include <Arduino.h>
#include <Wire.h>
#include <ArduinoBLE.h>
#include <LSM6DS3.h>

#include <dlinero-project-1_inferencing.h>

// =====================================================
// BLE CONFIG
// =====================================================
static const char* BLE_NAME_ADV  = "DOGFIT";
static const char* BLE_NAME_FULL = "DOGFIT-001";

static const char* SERVICE_UUID_STR = "0000ABCD-0000-1000-8000-00805F9B34FB";
static const char* RES_UUID_STR     = "0000ABCF-0000-1000-8000-00805F9B34FB";
static const char* ACK_UUID_STR     = "0000ABD0-0000-1000-8000-00805F9B34FB";

BLEService dogfitService(SERVICE_UUID_STR);

// 10 bytes/rec, 2 recs/notify = 20 bytes
static constexpr int REC_BYTES = 10;
static constexpr int MAX_RECS_PER_NOTIFY = 2;
static constexpr int NOTIFY_BYTES = REC_BYTES * MAX_RECS_PER_NOTIFY; // 20

BLECharacteristic resChar(RES_UUID_STR, BLENotify, NOTIFY_BYTES);
BLECharacteristic ackChar(ACK_UUID_STR, BLEWriteWithoutResponse | BLEWrite, 4);

static volatile bool g_connected = false;

// =====================================================
// BLE estándar de batería: 180F / 2A19
// =====================================================
BLEService batteryService("180F");
BLEUnsignedCharCharacteristic batteryLevelChar("2A19", BLERead | BLENotify); // 0..100

// =====================================================
// Battery (XIAO nRF52840 Sense)
// VBAT sense: P0.31, y P0.14 debe mantenerse LOW (no HIGH).
// =====================================================
#define VBAT_ADC_PIN   P0_31
#define VBAT_ENABLE    P0_14

#define ADC_BITS 12
#define ADC_MAX 4095

// Ajusta si quieres (tu calibración)
#define ADC_REF 3.37f
#define VBAT_MULTIPLIER 2.96f

// Anti-fantasma (batería desconectada con USB)
#define BAT_SAMPLE_COUNT       6
#define BAT_SAMPLE_DELAY_MS    2
#define BAT_FLOAT_SPAN_V       0.15f
#define BAT_CONNECT_SPAN_V     0.08f
#define VBAT_MIN_REAL          3.00f
#define VBAT_MAX_REAL          4.25f

static bool  g_batPresent = true;
static uint8_t g_batPct = 0;
static float g_batV = 0.0f;
static uint32_t g_lastBatMs = 0;
static const uint32_t BAT_UPDATE_MS = 2000;

static inline float readVbatOnce() {
  // Mantener P0.14 en LOW SIEMPRE
  pinMode(VBAT_ENABLE, OUTPUT);
  digitalWrite(VBAT_ENABLE, LOW);
  delayMicroseconds(200);

  analogReadResolution(ADC_BITS);
  uint16_t raw = analogRead(VBAT_ADC_PIN);

  return (raw / (float)ADC_MAX) * ADC_REF * VBAT_MULTIPLIER;
}

static inline void readVbatWindow(float &avg, float &vmin, float &vmax) {
  float sum = 0;
  vmin = 99.0f;
  vmax = 0.0f;

  for (int i = 0; i < BAT_SAMPLE_COUNT; i++) {
    float v = readVbatOnce();
    sum += v;
    if (v < vmin) vmin = v;
    if (v > vmax) vmax = v;
    delay(BAT_SAMPLE_DELAY_MS);
  }
  avg = sum / BAT_SAMPLE_COUNT;
}

static inline uint8_t vbatToPercent(float v) {
  if (v >= 4.20f) return 100;
  if (v >= 4.10f) return 95;
  if (v >= 4.00f) return 90;
  if (v >= 3.90f) return 80;
  if (v >= 3.80f) return 70;
  if (v >= 3.70f) return 60;
  if (v >= 3.60f) return 50;
  if (v >= 3.50f) return 40;
  if (v >= 3.40f) return 30;
  if (v >= 3.30f) return 20;
  return 10;
}

static void updateBatteryIfDue() {
  uint32_t now = millis();
  if (now - g_lastBatMs < BAT_UPDATE_MS) return;
  g_lastBatMs = now;

  float avg, vmin, vmax;
  readVbatWindow(avg, vmin, vmax);
  float span = vmax - vmin;

  bool looksReal = (avg >= VBAT_MIN_REAL && avg <= VBAT_MAX_REAL);

  if (g_batPresent) {
    if (!looksReal || span > BAT_FLOAT_SPAN_V) g_batPresent = false;
  } else {
    if (looksReal && span < BAT_CONNECT_SPAN_V) g_batPresent = true;
  }

  g_batV = avg;
  g_batPct = g_batPresent ? vbatToPercent(avg) : 0;

  // Actualiza característica estándar de batería
  batteryLevelChar.writeValue(g_batPct);
}

// =====================================================
// IMU (Seeed LSM6DS3 lib) - TU CONFIG BUENA
// =====================================================
LSM6DS3 myIMU(I2C_MODE, 0x6A);
static bool imuReady = false;
static uint32_t lastRetryMs = 0;

static bool initIMU() {
  if (myIMU.begin() != 0) {
    Serial.println("IMU no detectado...");
    return false;
  }

  myIMU.settings.accelRange      = 2;
  myIMU.settings.accelSampleRate = 104;
  myIMU.settings.gyroRange       = 245;
  myIMU.settings.gyroSampleRate  = 104;

  if (myIMU.begin() != 0) {
    Serial.println("IMU re-begin fallo");
    return false;
  }

  Serial.println("IMU OK");
  return true;
}

// =====================================================
// RAM CIRCULAR BUFFER + ACK (uint32 seq)
// =====================================================
static constexpr uint16_t MAX_RECORDS = 2048;

typedef struct {
  uint32_t t_ms;
  uint8_t  label;
  uint8_t  conf;
  uint32_t seq;
} LogRec;

static LogRec  g_buf[MAX_RECORDS];
static uint16_t g_head = 0;  // next write
static uint16_t g_tail = 0;  // oldest not acked
static uint16_t g_send = 0;  // next-to-send cursor
static uint32_t g_seq  = 0;

// throttling send
static const uint32_t SYNC_INTERVAL_MS = 120;
static uint32_t g_lastSyncMs = 0;

// =====================================================
// EI BUFFER
// =====================================================
static constexpr uint32_t SAMPLE_INTERVAL_MS = 20;
static uint32_t lastSampleMs = 0;

static constexpr int EI_FRAMES_PER_WINDOW = EI_CLASSIFIER_RAW_SAMPLE_COUNT;
static constexpr int EI_AXES              = EI_CLASSIFIER_RAW_SAMPLES_PER_FRAME;
static constexpr int EI_VALUES_PER_WINDOW = EI_FRAMES_PER_WINDOW * EI_AXES;

static float ei_ring[EI_VALUES_PER_WINDOW];
static int   ei_ring_head = 0;
static int   ei_frames_collected = 0;
static int   ei_frames_since_infer = 0;

static constexpr int EI_INFER_STRIDE_FRAMES = 150;

// =====================================================
// Helpers
// =====================================================
static inline uint16_t nextIdx(uint16_t i) { return (uint16_t)((i + 1) % MAX_RECORDS); }
static inline bool idxEq(uint16_t a, uint16_t b) { return a == b; }
static inline bool bufferEmpty() { return idxEq(g_head, g_tail); }

static inline void write_u32_le(uint8_t* dst, uint32_t v) {
  dst[0] = (uint8_t)(v & 0xFF);
  dst[1] = (uint8_t)((v >> 8) & 0xFF);
  dst[2] = (uint8_t)((v >> 16) & 0xFF);
  dst[3] = (uint8_t)((v >> 24) & 0xFF);
}

static inline uint32_t read_u32_le(const uint8_t* src) {
  return (uint32_t)src[0]
       | ((uint32_t)src[1] << 8)
       | ((uint32_t)src[2] << 16)
       | ((uint32_t)src[3] << 24);
}

// EI get_data
static int ei_get_data(size_t offset, size_t length, float* out_ptr) {
  for (size_t i = 0; i < length; i++) {
    size_t idx = (ei_ring_head + offset + i) % EI_VALUES_PER_WINDOW;
    out_ptr[i] = ei_ring[idx];
  }
  return 0;
}

// =====================================================
// BLE events
// =====================================================
static void onConnect(BLEDevice central) {
  (void)central;
  g_connected = true;
  g_send = g_tail;
  Serial.println("BLE connected");
}

static void onDisconnect(BLEDevice central) {
  (void)central;
  g_connected = false;
  Serial.println("BLE disconnected");
  BLE.advertise(); // igual que tu sketch bueno
}

// ACK handler (uint32 LE)
static void onAckWritten(BLEDevice central, BLECharacteristic characteristic) {
  (void)central;
  if (characteristic.valueLength() < 4) return;

  const uint8_t* v = characteristic.value();
  uint32_t ack = read_u32_le(v);

  while (!bufferEmpty()) {
    uint16_t t = g_tail;
    uint32_t s = g_buf[t].seq;
    if (s <= ack) {
      g_tail = nextIdx(g_tail);
      if (idxEq(g_send, t)) g_send = g_tail;
    } else {
      break;
    }
  }
}

static void storeRecord(uint32_t t_ms, uint8_t label, uint8_t conf) {
  g_buf[g_head].t_ms  = t_ms;
  g_buf[g_head].label = label;
  g_buf[g_head].conf  = conf;
  g_buf[g_head].seq   = g_seq++;

  uint16_t newHead = nextIdx(g_head);

  if (idxEq(newHead, g_tail)) {
    g_tail = nextIdx(g_tail);
    if (idxEq(g_send, g_tail)) g_send = g_tail;
  }

  g_head = newHead;
}

static void syncBacklog() {
  if (!g_connected) return;
  if (!resChar.subscribed()) return;
  if (bufferEmpty()) return;

  uint32_t now = millis();
  if (now - g_lastSyncMs < SYNC_INTERVAL_MS) return;
  g_lastSyncMs = now;

  if (idxEq(g_send, g_head)) g_send = g_tail;

  uint8_t payload[NOTIFY_BYTES];
  uint8_t count = 0;

  uint16_t cursor = g_send;
  while (!idxEq(cursor, g_head) && count < MAX_RECS_PER_NOTIFY) {
    const LogRec& r = g_buf[cursor];

    uint8_t* p = payload + (count * REC_BYTES);
    write_u32_le(p + 0, r.t_ms);
    p[4] = r.label;
    p[5] = r.conf;
    write_u32_le(p + 6, r.seq);

    cursor = nextIdx(cursor);
    count++;
  }

  if (count == 0) return;

  g_send = cursor;
  resChar.writeValue(payload, (int)(count * REC_BYTES));
}

// =====================================================
// BLE setup
// =====================================================
static void setupBle() {
  if (!BLE.begin()) {
    Serial.println("BLE.begin failed");
    while (1) delay(1000);
  }

  BLE.setLocalName(BLE_NAME_ADV);
  BLE.setDeviceName(BLE_NAME_FULL);

  BLE.setEventHandler(BLEConnected, onConnect);
  BLE.setEventHandler(BLEDisconnected, onDisconnect);

  ackChar.setEventHandler(BLEWritten, onAckWritten);

  // Servicio propietario
  dogfitService.addCharacteristic(resChar);
  dogfitService.addCharacteristic(ackChar);
  BLE.addService(dogfitService);
  BLE.setAdvertisedService(dogfitService);

  // Servicio estándar batería
  batteryService.addCharacteristic(batteryLevelChar);
  BLE.addService(batteryService);
  batteryLevelChar.writeValue((uint8_t)0);

  // init
  uint8_t z[1] = {0};
  resChar.writeValue(z, 1);

  BLE.advertise();
  Serial.println("Advertising ON - busca DOGFIT / DOGFIT-001");
}

// =====================================================
// setup / loop
// =====================================================
void setup() {
  Serial.begin(115200);
  delay(800);

  Serial.println("DOGFIT: EI + BLE + ACK(uint32) + LSM6DS3 (Seeed) + BAS(180F/2A19)");

  Wire.begin();
  delay(50);

  // IMU como tu sketch bueno
  imuReady = initIMU();

  setupBle();

  Serial.println("Setup OK");
}

void loop() {
  BLE.poll();

  uint32_t now = millis();

  // Actualiza batería por BAS cada ~2s
  updateBatteryIfDue();

  // Retry IMU if not ready
  if (!imuReady && (now - lastRetryMs >= 2000)) {
    lastRetryMs = now;
    imuReady = initIMU();
  }

  // Flush backlog cuando se pueda
  syncBacklog();

  if (!imuReady) {
    delay(10);
    return;
  }

  // Sampling interval
  if (now - lastSampleMs < SAMPLE_INTERVAL_MS) return;
  lastSampleMs = now;

  // Read IMU (floats)
  const float G_TO_MS2 = 9.80665f;

  float ax = myIMU.readFloatAccelX() * G_TO_MS2;
  float ay = myIMU.readFloatAccelY() * G_TO_MS2;
  float az = myIMU.readFloatAccelZ() * G_TO_MS2;

  float gx = myIMU.readFloatGyroX();
  float gy = myIMU.readFloatGyroY();
  float gz = myIMU.readFloatGyroZ();

  // EI ring buffer (6 axes)
  ei_ring[ei_ring_head] = ax; ei_ring_head = (ei_ring_head + 1) % EI_VALUES_PER_WINDOW;
  ei_ring[ei_ring_head] = ay; ei_ring_head = (ei_ring_head + 1) % EI_VALUES_PER_WINDOW;
  ei_ring[ei_ring_head] = az; ei_ring_head = (ei_ring_head + 1) % EI_VALUES_PER_WINDOW;
  ei_ring[ei_ring_head] = gx; ei_ring_head = (ei_ring_head + 1) % EI_VALUES_PER_WINDOW;
  ei_ring[ei_ring_head] = gy; ei_ring_head = (ei_ring_head + 1) % EI_VALUES_PER_WINDOW;
  ei_ring[ei_ring_head] = gz; ei_ring_head = (ei_ring_head + 1) % EI_VALUES_PER_WINDOW;

  ei_frames_collected = min(ei_frames_collected + 1, EI_FRAMES_PER_WINDOW);
  ei_frames_since_infer++;

  if (ei_frames_collected < EI_FRAMES_PER_WINDOW) return;
  if (ei_frames_since_infer < EI_INFER_STRIDE_FRAMES) return;
  ei_frames_since_infer = 0;

  // Run EI
  signal_t signal;
  signal.total_length = EI_VALUES_PER_WINDOW;
  signal.get_data = &ei_get_data;

  ei_impulse_result_t result = {0};
  EI_IMPULSE_ERROR err = run_classifier(&signal, &result, false);
  if (err != EI_IMPULSE_OK) return;

  int best_i = 0;
  float best_v = result.classification[0].value;
  for (size_t i = 1; i < EI_CLASSIFIER_LABEL_COUNT; i++) {
    if (result.classification[i].value > best_v) {
      best_v = result.classification[i].value;
      best_i = (int)i;
    }
  }

  uint8_t conf = (uint8_t)min(100.0f, max(0.0f, best_v * 100.0f));

  // Store record; removal only after ACK
  storeRecord(now, (uint8_t)best_i, conf);
}