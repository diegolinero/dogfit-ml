#include <Arduino.h>
#include <Wire.h>
#include <FS.h>
#include <SPIFFS.h>
#include <NimBLEDevice.h>

// Edge Impulse (ajusta el nombre si es diferente en tu proyecto)
#include <dlinero-project-1_inferencing.h>

// ────────────────────────────────────────────────
//  ORDEN IMPORTANTE: primero includes → constantes → structs → prototipos → globals → funciones → setup/loop
// ────────────────────────────────────────────────

// ---------------------
// HW / I2C
// ---------------------
static const int I2C_SDA = 8;
static const int I2C_SCL = 9;
static const uint32_t I2C_FREQ = 400000;
static const uint8_t MPU_ADDR = 0x68;

// ---------------------
// BLE
// ---------------------
static const char* BLE_NAME_ADV  = "DOGFIT";
static const char* BLE_NAME_FULL = "DOGFIT-001";
static const char* SERVICE_UUID_STR = "0000ABCD-0000-1000-8000-00805F9B34FB";
static const char* RES_UUID_STR     = "0000ABCF-0000-1000-8000-00805F9B34FB";

static NimBLEServer*         g_server  = nullptr;
static NimBLEAdvertising*    g_adv     = nullptr;
static NimBLECharacteristic* g_resChar = nullptr;

// ---------------------
// Battery knobs & Sampling
// ---------------------
static constexpr bool ENABLE_LIVE_STREAM = false;
static constexpr int  EI_INFER_STRIDE_FRAMES = 150;
static constexpr uint8_t  SYNC_BATCH_RECS  = 10;
static constexpr uint32_t SYNC_INTERVAL_MS = 200;

static const uint32_t SAMPLE_INTERVAL_MS = 20;
static uint32_t lastSampleMs = 0;

// ---------------------
// Edge Impulse ring buffer
// ---------------------
static constexpr int EI_FRAMES_PER_WINDOW = EI_CLASSIFIER_RAW_SAMPLE_COUNT;
static constexpr int EI_AXES              = EI_CLASSIFIER_RAW_SAMPLES_PER_FRAME;
static constexpr int EI_VALUES_PER_WINDOW = EI_FRAMES_PER_WINDOW * EI_AXES;

static float ei_ring[EI_VALUES_PER_WINDOW];
static int   ei_ring_head = 0;
static int   ei_frames_collected = 0;
static int   ei_frames_since_infer = 0;

// ────────────── ESTRUCTURAS ────────────── (aquí MUY arriba)
// ──────────────────────────────────────────
static const char* DOGFIT_LOG_PATH  = "/dogfit_log.bin";
static const char* DOGFIT_META_PATH = "/dogfit_meta.bin";
static const uint32_t REC_SIZE = 8;

typedef struct __attribute__((packed)) {
    uint32_t t_ms;
    uint8_t  label;
    uint8_t  conf;
    uint16_t seq;
} LogRec;

typedef struct __attribute__((packed)) {
    uint32_t magic;     // 0xD06F1A61
    uint32_t wpos;
    uint32_t rpos;
    uint16_t seq;
    uint8_t  full;
    uint8_t  reserved;
} LogMeta;

// ────────────── PROTOTIPOS de funciones que usan LogRec ──────────────
static bool readRecAt(uint32_t pos, LogRec& out);
static bool popOne(LogRec& out);
static void writeRec(uint32_t t_ms, uint8_t label, uint8_t conf);
static void syncBacklogIfConnected();

// ────────────── VARIABLES GLOBALES ──────────────
static LogMeta g_meta;
static File g_logFile;
static uint32_t g_meta_dirty = 0;

static uint32_t g_log_size_bytes = 0;
static uint32_t g_log_capacity   = 0;
static uint32_t g_last_sync_ms   = 0;

static inline bool bleConnected() {
    return (g_server && g_server->getConnectedCount() > 0);
}

// ────────────── FUNCIONES AUXILIARES (MPU, BLE, etc.) ──────────────

static bool mpuWrite(uint8_t reg, uint8_t val) {
    Wire.beginTransmission(MPU_ADDR);
    Wire.write(reg);
    Wire.write(val);
    return (Wire.endTransmission() == 0);
}

static bool mpuRead(uint8_t reg, uint8_t* buf, size_t len) {
    Wire.beginTransmission(MPU_ADDR);
    Wire.write(reg);
    if (Wire.endTransmission(false) != 0) return false;
    if (Wire.requestFrom((int)MPU_ADDR, (int)len) != (int)len) return false;
    for (size_t i = 0; i < len; i++) buf[i] = Wire.read();
    return true;
}

static bool mpuInit() {
    delay(50);
    if (!mpuWrite(0x6B, 0x00)) return false;
    delay(10);
    if (!mpuWrite(0x1C, 0x00)) return false;
    if (!mpuWrite(0x1B, 0x00)) return false;
    if (!mpuWrite(0x1A, 0x03)) return false;
    return true;
}

static bool mpuReadRaw(int16_t& ax, int16_t& ay, int16_t& az,
                       int16_t& gx, int16_t& gy, int16_t& gz) {
    uint8_t buf[14];
    if (!mpuRead(0x3B, buf, 14)) return false;
    ax = (buf[0] << 8) | buf[1];
    ay = (buf[2] << 8) | buf[3];
    az = (buf[4] << 8) | buf[5];
    gx = (buf[8] << 8) | buf[9];
    gy = (buf[10] << 8) | buf[11];
    gz = (buf[12] << 8) | buf[13];
    return true;
}

static void bleNotifyBytes(const uint8_t* data, size_t len) {
    if (!bleConnected() || !g_resChar) return;
    g_resChar->setValue(data, len);
    g_resChar->notify();
}

static void setupBle() {
    Serial.println("BLE init");
    NimBLEDevice::deinit(true);
    delay(200);

    NimBLEDevice::init(BLE_NAME_FULL);
    NimBLEDevice::setPower(ESP_PWR_LVL_P6);

    g_server = NimBLEDevice::createServer();

    NimBLEUUID serviceUuid(SERVICE_UUID_STR);
    NimBLEUUID resultUuid(RES_UUID_STR);

    NimBLEService* svc = g_server->createService(serviceUuid);
    g_resChar = svc->createCharacteristic(resultUuid, NIMBLE_PROPERTY::NOTIFY);
    svc->start();

    g_adv = NimBLEDevice::getAdvertising();
    g_adv->stop();
    g_adv->reset();

    NimBLEAdvertisementData advData;
    advData.setFlags(0x06);
    advData.setName(BLE_NAME_ADV);

    NimBLEAdvertisementData scanData;
    scanData.setCompleteServices(serviceUuid);

    g_adv->setAdvertisementData(advData);
    g_adv->setScanResponseData(scanData);
    g_adv->setMinInterval(160);
    g_adv->setMaxInterval(240);
    g_adv->start();

    Serial.println("Advertising started");
}

static int ei_get_data(size_t offset, size_t length, float* out_ptr) {
    for (size_t i = 0; i < length; i++) {
        size_t idx = (ei_ring_head + offset + i) % EI_VALUES_PER_WINDOW;
        out_ptr[i] = ei_ring[idx];
    }
    return 0;
}

// ────────────── FUNCIONES DE LOG ──────────────

static void metaDefault() {
    g_meta.magic = 0xD06F1A61u;
    g_meta.wpos = 0;
    g_meta.rpos = 0;
    g_meta.seq = 0;
    g_meta.full = 0;
    g_meta.reserved = 0;
}

static void metaSave() {
    File f = SPIFFS.open(DOGFIT_META_PATH, "w");
    if (!f) return;
    f.write((const uint8_t*)&g_meta, sizeof(g_meta));
    f.close();
    g_meta_dirty = 0;
}

static bool computeLogSizeFromSPIFFS() {
    const uint32_t total = SPIFFS.totalBytes();
    const uint32_t MARGIN = 128u * 1024u;

    if (total < (512u * 1024u)) return false;

    uint32_t target = (total > MARGIN) ? (total - MARGIN) : (total / 2);
    target = (target / REC_SIZE) * REC_SIZE;

    const uint32_t MIN_SZ = 256u * 1024u;
    if (target < MIN_SZ) target = (MIN_SZ / REC_SIZE) * REC_SIZE;

    g_log_size_bytes = target;
    g_log_capacity   = g_log_size_bytes / REC_SIZE;
    return true;
}

static void metaLoadOrInit() {
    metaDefault();

    if (!SPIFFS.exists(DOGFIT_META_PATH)) {
        metaSave();
        return;
    }

    File f = SPIFFS.open(DOGFIT_META_PATH, "r");
    if (!f) { metaSave(); return; }
    if (f.size() != (int)sizeof(LogMeta)) { f.close(); metaSave(); return; }

    LogMeta m;
    size_t n = f.readBytes((char*)&m, sizeof(m));
    f.close();

    if (n != sizeof(m)) { metaSave(); return; }
    if (m.magic != 0xD06F1A61u) { metaSave(); return; }
    if (m.wpos >= g_log_size_bytes || m.rpos >= g_log_size_bytes) { metaSave(); return; }
    if ((m.wpos % REC_SIZE) != 0 || (m.rpos % REC_SIZE) != 0) { metaSave(); return; }

    g_meta = m;
}

static bool ensureLogFileSizedAndOpen() {
    if (!SPIFFS.exists(DOGFIT_LOG_PATH)) {
        Serial.println("Creating dogfit_log.bin");
        File f = SPIFFS.open(DOGFIT_LOG_PATH, "w");
        if (!f) return false;

        static uint8_t zeroBuf[4096];
        memset(zeroBuf, 0, sizeof(zeroBuf));

        uint32_t written = 0;
        while (written < g_log_size_bytes) {
            uint32_t chunk = min<uint32_t>((uint32_t)sizeof(zeroBuf), (g_log_size_bytes - written));
            size_t w = f.write(zeroBuf, chunk);
            if (w != chunk) { f.close(); return false; }
            written += chunk;
            yield();
        }
        f.flush();
        f.close();

        metaDefault();
        metaSave();
        Serial.println("Log created");
    }

    g_logFile = SPIFFS.open(DOGFIT_LOG_PATH, "r+");
    return (bool)g_logFile;
}

static inline bool logEmpty() {
    return (!g_meta.full && (g_meta.wpos == g_meta.rpos));
}

static bool readRecAt(uint32_t pos, LogRec& out) {
    if (!g_logFile) return false;
    g_logFile.seek(pos, SeekSet);
    size_t n = g_logFile.readBytes((char*)&out, sizeof(out));
    return (n == sizeof(out));
}

static void writeRec(uint32_t t_ms, uint8_t label, uint8_t conf) {
    if (!g_logFile || g_log_size_bytes == 0) return;

    LogRec r;
    r.t_ms = t_ms;
    r.label = label;
    r.conf = conf;
    r.seq = g_meta.seq++;

    g_logFile.seek(g_meta.wpos, SeekSet);
    g_logFile.write((const uint8_t*)&r, sizeof(r));
    g_logFile.flush();

    uint32_t next_wpos = g_meta.wpos + REC_SIZE;
    if (next_wpos >= g_log_size_bytes) next_wpos = 0;

    if (g_meta.full) {
        uint32_t next_rpos = g_meta.rpos + REC_SIZE;
        if (next_rpos >= g_log_size_bytes) next_rpos = 0;
        g_meta.rpos = next_rpos;
    } else {
        if (next_wpos == g_meta.rpos) g_meta.full = 1;
    }

    g_meta.wpos = next_wpos;

    g_meta_dirty++;
    if (g_meta_dirty >= 64) metaSave();
}

static bool popOne(LogRec& out) {
    if (logEmpty() || g_log_size_bytes == 0) return false;
    if (!readRecAt(g_meta.rpos, out)) return false;

    uint32_t next_rpos = g_meta.rpos + REC_SIZE;
    if (next_rpos >= g_log_size_bytes) next_rpos = 0;
    g_meta.rpos = next_rpos;

    g_meta.full = 0;

    g_meta_dirty++;
    if (g_meta_dirty >= 64) metaSave();

    return true;
}

static void syncBacklogIfConnected() {
    if (!bleConnected() || logEmpty() || !g_resChar) return;

    uint32_t now = millis();
    if (now - g_last_sync_ms < SYNC_INTERVAL_MS) return;
    g_last_sync_ms = now;

    uint8_t payload[SYNC_BATCH_RECS * REC_SIZE];
    uint8_t count = 0;

    for (uint8_t i = 0; i < SYNC_BATCH_RECS; i++) {
        LogRec r;
        if (!popOne(r)) break;

        uint8_t* p = payload + (count * REC_SIZE);
        p[0] = (uint8_t)(r.t_ms & 0xFF);
        p[1] = (uint8_t)((r.t_ms >> 8) & 0xFF);
        p[2] = (uint8_t)((r.t_ms >> 16) & 0xFF);
        p[3] = (uint8_t)((r.t_ms >> 24) & 0xFF);
        p[4] = r.label;
        p[5] = r.conf;
        p[6] = (uint8_t)(r.seq & 0xFF);
        p[7] = (uint8_t)((r.seq >> 8) & 0xFF);

        count++;
    }

    if (count > 0) {
        bleNotifyBytes(payload, (size_t)count * REC_SIZE);
    }
}

// ────────────── SETUP y LOOP ──────────────

void setup() {
    Serial.begin(115200);
    delay(600);
    Serial.println("DOGFIT: EI + BLE + SPIFFS log (battery optimized)");

    if (!SPIFFS.begin(true)) {
        Serial.println("SPIFFS begin failed");
        while (true) delay(1000);
    }

    if (!computeLogSizeFromSPIFFS()) {
        Serial.println("SPIFFS too small");
        while (true) delay(1000);
    }

    metaLoadOrInit();

    if (!ensureLogFileSizedAndOpen()) {
        Serial.println("Cannot open/create log");
        while (true) delay(1000);
    }

    double hours = (double)g_log_capacity * 3.0 / 3600.0;
    Serial.print("SPIFFS total="); Serial.print(SPIFFS.totalBytes());
    Serial.print(" used="); Serial.println(SPIFFS.usedBytes());
    Serial.print("log bytes="); Serial.print(g_log_size_bytes);
    Serial.print(" recs="); Serial.println(g_log_capacity);
    Serial.print("approx hours @3s="); Serial.println(hours, 2);

    Wire.begin(I2C_SDA, I2C_SCL, I2C_FREQ);
    delay(50);

    Wire.beginTransmission(MPU_ADDR);
    if (Wire.endTransmission() != 0) {
        Serial.println("MPU not found at 0x68");
        while (true) delay(1000);
    }

    if (!mpuInit()) {
        Serial.println("MPU init failed");
        while (true) delay(1000);
    }
    Serial.println("MPU OK");

    setupBle();
    Serial.println("Setup OK");
}

void loop() {
    if (g_adv && !g_adv->isAdvertising() && g_server && g_server->getConnectedCount() == 0) {
        g_adv->start();
    }

    syncBacklogIfConnected();

    uint32_t now = millis();
    if (now - lastSampleMs < SAMPLE_INTERVAL_MS) return;
    lastSampleMs = now;

    int16_t axr, ayr, azr, gxr, gyr, gzr;
    if (!mpuReadRaw(axr, ayr, azr, gxr, gyr, gzr)) return;

    const float G_TO_MS2 = 9.80665f;
    float ax = (axr / 16384.0f) * G_TO_MS2;
    float ay = (ayr / 16384.0f) * G_TO_MS2;
    float az = (azr / 16384.0f) * G_TO_MS2;
    float gx = (gxr / 131.0f);
    float gy = (gyr / 131.0f);
    float gz = (gzr / 131.0f);

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

    writeRec(now, (uint8_t)best_i, conf);

    if (ENABLE_LIVE_STREAM && bleConnected()) {
        uint8_t p[8];
        uint16_t seq = (uint16_t)(g_meta.seq - 1);
        p[0] = (uint8_t)(now & 0xFF);
        p[1] = (uint8_t)((now >> 8) & 0xFF);
        p[2] = (uint8_t)((now >> 16) & 0xFF);
        p[3] = (uint8_t)((now >> 24) & 0xFF);
        p[4] = (uint8_t)best_i;
        p[5] = conf;
        p[6] = (uint8_t)(seq & 0xFF);
        p[7] = (uint8_t)((seq >> 8) & 0xFF);
        bleNotifyBytes(p, 8);
    }
}