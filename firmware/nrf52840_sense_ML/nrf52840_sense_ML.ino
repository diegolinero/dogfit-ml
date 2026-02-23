#include <Arduino.h>
#include <ArduinoBLE.h>
#include <Arduino_LSM6DS3.h>

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

// 10 bytes/rec, 2 recs/notify = 20 bytes (entra en MTU default)
static constexpr int REC_BYTES = 10;
static constexpr int MAX_RECS_PER_NOTIFY = 2;
static constexpr int NOTIFY_BYTES = REC_BYTES * MAX_RECS_PER_NOTIFY; // 20

BLECharacteristic resChar(RES_UUID_STR, BLENotify, NOTIFY_BYTES);
BLECharacteristic ackChar(ACK_UUID_STR, BLEWriteWithoutResponse | BLEWrite, 4);

static volatile bool g_connected = false;

// =====================================================
// RAM CIRCULAR BUFFER + ACK
// =====================================================
static constexpr uint16_t MAX_RECORDS = 2048; // 2048 * (struct) ~ OK en RAM

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

// =====================================================
// EI get_data
// =====================================================
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
}

// ACK handler (uint32 LE)
static void onAckWritten(BLEDevice central, BLECharacteristic characteristic) {
  (void)central;
  if (characteristic.valueLength() < 4) return;

  const uint8_t* v = characteristic.value();
  uint32_t ack = read_u32_le(v);

  // Advance tail while seq <= ack
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

// =====================================================
// Store record (removed only by ACK)
// =====================================================
static void storeRecord(uint32_t t_ms, uint8_t label, uint8_t conf) {
  g_buf[g_head].t_ms  = t_ms;
  g_buf[g_head].label = label;
  g_buf[g_head].conf  = conf;
  g_buf[g_head].seq   = g_seq++;

  uint16_t newHead = nextIdx(g_head);

  // If full, drop oldest (advance tail)
  if (idxEq(newHead, g_tail)) {
    g_tail = nextIdx(g_tail);
    if (idxEq(g_send, g_tail)) g_send = g_tail;
  }

  g_head = newHead;
}

// =====================================================
// Send backlog (does NOT delete; only ACK deletes)
// =====================================================
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

    // pack 10 bytes: t_ms(4) label(1) conf(1) seq(4)
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

  dogfitService.addCharacteristic(resChar);
  dogfitService.addCharacteristic(ackChar);

  BLE.addService(dogfitService);
  BLE.setAdvertisedService(dogfitService);

  uint8_t z[1] = {0};
  resChar.writeValue(z, 1);

  BLE.advertise();
  Serial.println("BLE advertising");
}

// =====================================================
// setup / loop
// =====================================================
void setup() {
  Serial.begin(115200);
  delay(600);

  if (!IMU.begin()) {
    Serial.println("IMU.begin failed (Arduino_LSM6DS3)");
    while (1) delay(1000);
  }
  Serial.println("IMU OK");

  setupBle();
  Serial.println("Setup OK");
}

void loop() {
  BLE.poll();

  // flush backlog
  syncBacklog();

  // sample rate
  uint32_t now = millis();
  if (now - lastSampleMs < SAMPLE_INTERVAL_MS) return;
  lastSampleMs = now;

  // Read IMU
  float ax_g, ay_g, az_g;
  float gx_dps, gy_dps, gz_dps;

  if (!IMU.accelerationAvailable() || !IMU.gyroscopeAvailable()) return;

  IMU.readAcceleration(ax_g, ay_g, az_g);
  IMU.readGyroscope(gx_dps, gy_dps, gz_dps);

  // Convert accel from g -> m/s^2
  const float G_TO_MS2 = 9.80665f;
  float ax = ax_g * G_TO_MS2;
  float ay = ay_g * G_TO_MS2;
  float az = az_g * G_TO_MS2;

  float gx = gx_dps;
  float gy = gy_dps;
  float gz = gz_dps;

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

  // Store record (removed only after ACK)
  storeRecord(now, (uint8_t)best_i, conf);
}