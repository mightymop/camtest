// rtp_fixer_rfc2435.c
// JNI library with RFC2435 option
// Build as libRtpConvertProxy.so
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <pthread.h>
#include <time.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <errno.h>
#include <stdbool.h>
#include <android/log.h>

#define LOG_TAG "RtpConvertProxy"
#define ALOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define PROTO_PORT 2224
#define PROTO_HEADER_SIZE 20
#define MAX_UDP_PAYLOAD 65507
#define RTP_HEADER_SIZE 12
#define RTP_PAYLOAD_TYPE_MJPEG 26
#define RTP_CLOCK_RATE 90000U

static int recv_fd = -1;
static int send_fd = -1;
static struct sockaddr_in send_addr;
static volatile bool running = false;
static pthread_t worker_thread;
static uint32_t ssrc = 0x12345678;
static bool use_proprietary_seq_as_rtp = true;
static bool use_rfc2435 = false;
static bool save_debug_image = false;
static int assumed_fps = 30;
static uint32_t frame_counter = 0;

// helpers to read little endian
static inline uint16_t le16(const uint8_t *p) {
    return (uint16_t) p[0] | ((uint16_t) p[1] << 8);
}

static inline uint32_t le32(const uint8_t *p) {
    return (uint32_t) p[0] | ((uint32_t) p[1] << 8) | ((uint32_t) p[2] << 16) |
           ((uint32_t) p[3] << 24);
}

// monotonic base (90kHz)
static uint32_t get_monotonic_rtp_ts_base() {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
    uint64_t r = (uint64_t) ts.tv_sec * RTP_CLOCK_RATE +
                 (uint64_t) ts.tv_nsec / (1000000000UL / RTP_CLOCK_RATE);
    return (uint32_t) r;
}

// parse JPEG SOF0/SOF2 to get width/height
// returns 0 on success and sets *w,*h, otherwise -1
static int parse_jpeg_size(const uint8_t *data, size_t len, int *w, int *h) {
    if (!data || len < 4) return -1;
    // Need to see SOI 0xFF 0xD8 at start
    size_t i = 0;
    if (data[0] != 0xFF || data[1] != 0xD8) {
        // still try to search for SOI within the buffer
        for (i = 0; i + 1 < len; ++i) {
            if (data[i] == 0xFF && data[i + 1] == 0xD8) { break; }
        }
        if (i + 1 >= len) return -1;
    }
    i = (data[0] == 0xFF && data[1] == 0xD8) ? 2 : i + 2;
    // scan markers
    while (i + 4 < len) {
        if (data[i] != 0xFF) {
            i++;
            continue;
        }
        // skip padding 0xFF
        while (i < len && data[i] == 0xFF) i++;
        if (i >= len) break;
        uint8_t marker = data[i++];
        // Standalone markers (no length) like 0xD0-0xD9
        if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD9)) {
            continue;
        }
        // need 2 bytes length
        if (i + 1 >= len) break;
        uint16_t seglen = (data[i] << 8) | data[i + 1];
        if (seglen < 2) return -1;
        // SOF markers: 0xC0..0xC3 typically (baseline, progressive)
        if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2 || marker == 0xC3 ||
            marker == 0xC5 || marker == 0xC6 || marker == 0xC7 || marker == 0xC9 ||
            marker == 0xCA || marker == 0xCB || marker == 0xCD || marker == 0xCE ||
            marker == 0xCF) {
            // ensure we have enough bytes for sample precision(1) + height(2)+width(2)
            if (i + seglen > len) break;
            // offset inside segment: 2 bytes length already, then sample precision 1 byte
            size_t off = i + 2; // position of sample precision
            if (off + 3 >= len) return -1;
            uint16_t height = (data[off + 1] << 8) | data[off + 2];
            uint16_t width = (data[off + 3] << 8) | data[off + 4];
            *w = (int) width;
            *h = (int) height;
            return 0;
        }
        // skip to next segment: seglen includes length bytes
        i += seglen;
    }
    return -1;
}

// Save JPEG data to file for debugging
static void save_jpeg_to_file(const uint8_t *jpeg_data, size_t jpeg_size, uint32_t frame_seq, uint32_t offset, bool is_complete_frame) {
    char filename[128];

    if (!save_debug_image)
        return;

    if (is_complete_frame) {
        snprintf(filename, sizeof(filename), "/sdcard/Download/frame_%06u_complete.jpg", frame_seq);
    } else {
        snprintf(filename, sizeof(filename), "/sdcard/Download/frame_%06u_offset_%06u.jpg", frame_seq, offset);
    }

    FILE *file = fopen(filename, "wb");
    if (file == NULL) {
        ALOGE("Failed to open file for writing: %s", filename);
        return;
    }

    size_t written = fwrite(jpeg_data, 1, jpeg_size, file);
    fclose(file);

    if (written == jpeg_size) {
        ALOGV("Saved JPEG to %s (%zu bytes)", filename, jpeg_size);

        // Also save first 16 bytes for debugging
        ALOGI("JPEG start bytes: %02X %02X %02X %02X %02X %02X %02X %02X",
              jpeg_data[0], jpeg_data[1], jpeg_data[2], jpeg_data[3],
              jpeg_data[4], jpeg_data[5], jpeg_data[6], jpeg_data[7]);
    } else {
        ALOGE("Failed to write complete JPEG to %s (wrote %zu of %zu bytes)",
              filename, written, jpeg_size);
    }

    save_debug_image=false;
}

struct proto_hdr {
    uint8_t type;
    uint8_t reserved1;
    uint16_t blocksize;    // little endian
    uint32_t sequence;     // little endian
    uint32_t frame_size;   // little endian
    uint32_t offset;       // little endian
    uint32_t timestamp;    // little endian
};

static void
build_rtp_header(uint8_t *buf, uint8_t payload_type, bool marker, uint16_t seq, uint32_t ts,
                 uint32_t ssrc_in) {
    buf[0] = 0x80;
    buf[1] = (marker ? 0x80 : 0x00) | (payload_type & 0x7f);
    uint16_t seq_n = htons(seq);
    memcpy(buf + 2, &seq_n, 2);
    uint32_t ts_n = htonl(ts);
    memcpy(buf + 4, &ts_n, 4);
    uint32_t ssrc_n = htonl(ssrc_in);
    memcpy(buf + 8, &ssrc_n, 4);
}

static void *worker_func(void *arg) {
    (void) arg;
    ALOGI("worker started, listening on UDP %d", PROTO_PORT);

    static uint8_t recv_buf[MAX_UDP_PAYLOAD];
    static uint8_t send_buf[RTP_HEADER_SIZE + 8 + MAX_UDP_PAYLOAD];
    static uint8_t frame_buffer[200 * 1024]; // Buffer für komplette Frames (200KB)
    static size_t frame_buffer_used = 0;
    static uint32_t current_frame_seq = 0;

    ssize_t rlen;
    struct sockaddr_in cli_addr;
    socklen_t cli_len = sizeof(cli_addr);

    uint32_t current_frame_size = 0;
    uint32_t last_frame_sequence = 0xffffffff;
    uint32_t rtp_timestamp = get_monotonic_rtp_ts_base();
    uint64_t frames_sent = 0;

    while (running) {
        rlen = recvfrom(recv_fd, recv_buf, sizeof(recv_buf), 0, (struct sockaddr *) &cli_addr, &cli_len);
        if (rlen < 0) {
            if (errno == EINTR) continue;
            ALOGE("recvfrom error: %s", strerror(errno));
            break;
        }

        // NEU: Prüfe auf kombinierte Pakete
        bool is_combined_packet = (rlen > 1500); // Größer als typische MTU
        ALOGD("Received packet: total=%zd bytes, combined=%s",
              rlen, is_combined_packet ? "YES" : "NO");

        // NEU: Mehrere Frames in einem Paket verarbeiten
        size_t processed = 0;
        int fragment_count = 0;

        while (processed < (size_t)rlen) {
            // Prüfe ob genug Daten für Header vorhanden
            if (processed + PROTO_HEADER_SIZE > (size_t)rlen) {
                ALOGW("Incomplete header at offset %zu (need %d, have %zd)",
                      processed, PROTO_HEADER_SIZE, rlen - processed);
                break;
            }

            // Header parsen
            struct proto_hdr hdr;
            hdr.type = recv_buf[processed];
            hdr.reserved1 = recv_buf[processed + 1];
            hdr.blocksize = le16(recv_buf + processed + 2);
            hdr.sequence = le32(recv_buf + processed + 4);
            hdr.frame_size = le32(recv_buf + processed + 8);
            hdr.offset = le32(recv_buf + processed + 12);
            hdr.timestamp = le32(recv_buf + processed + 16);

            // Prüfe ob komplettes Fragment im Buffer ist
            if (processed + PROTO_HEADER_SIZE + hdr.blocksize > (size_t)rlen) {
                ALOGW("Incomplete fragment at offset %zu: need %u bytes, have %zd",
                      processed, hdr.blocksize, rlen - processed - PROTO_HEADER_SIZE);
                break;
            }

            fragment_count++;

            // NEU: Detailliertes Logging für kombinierte Pakete
            if (is_combined_packet) {
                ALOGD("Combined packet fragment %d: type=0x%02X, seq=%u, offset=%u, size=%u, pos=%zu",
                      fragment_count, hdr.type, hdr.sequence, hdr.offset, hdr.blocksize, processed);
            }

            uint8_t *payload_ptr = recv_buf + processed + PROTO_HEADER_SIZE;
            size_t payload_len = hdr.blocksize;

            // DEBUG: Type analysieren
            ALOGD("Fragment %d - Raw type: 0x%02X", fragment_count, hdr.type);

            // LAST_FREG_MAKER prüfen (Bit 7 = 0x80)
            bool is_last_fragment = (hdr.type & 0x80) != 0;
            uint8_t data_type = hdr.type & 0x7F;  // Untere 7 Bits

            ALOGD("Fragment %d - Data type: %d, Last fragment: %d, Seq: %u, Offset: %u, FrameSize: %u",
                  fragment_count, data_type, is_last_fragment, hdr.sequence, hdr.offset, hdr.frame_size);

            // Nur JPEG-Video-Daten verarbeiten (2)
            if (data_type != 2) {
                ALOGD("Skipping non-JPEG packet type: %d", data_type);
                processed += PROTO_HEADER_SIZE + payload_len;
                continue;
            }

            bool new_frame = (hdr.offset == 0);
            int img_width = 0, img_height = 0;

            // Frame-Buffer für komplette JPEGs verwalten
            if (new_frame) {
                // Neuer Frame startet - Buffer zurücksetzen
                frame_buffer_used = 0;
                current_frame_seq = hdr.sequence;
                ALOGV("=== NEW FRAME START: seq=%u, expected_size=%u ===", hdr.sequence, hdr.frame_size);
            }

            // NEU: JPEG Header Validierung
            if (hdr.offset == 0) {
                // Prüfe JPEG Start Marker
                if (payload_len >= 2) {
                    if (payload_ptr[0] == 0xFF && payload_ptr[1] == 0xD8) {
                        ALOGD("Valid JPEG SOI marker found");
                    } else {
                        ALOGW("INVALID JPEG SOI marker: %02X %02X - data might be corrupted",
                              payload_ptr[0], payload_ptr[1]);

                        // NEU: Versuche korrumpierte JPEGs zu erkennen
                        if (payload_ptr[0] == 0x00 && payload_ptr[1] == 0x00) {
                            ALOGW("H264 start code detected in JPEG data - STREAM CORRUPTION!");
                        }
                    }
                }
            }

            // Daten zum Frame-Buffer hinzufügen
            if (frame_buffer_used + payload_len <= sizeof(frame_buffer)) {
                memcpy(frame_buffer + frame_buffer_used, payload_ptr, payload_len);
                frame_buffer_used += payload_len;
                ALOGD("Added %zu bytes to frame buffer, total: %zu", payload_len, frame_buffer_used);
            } else {
                ALOGE("Frame buffer overflow! Cannot add %zu bytes (already %zu used)",
                      payload_len, frame_buffer_used);
            }

            // Kompletten Frame speichern wenn letztes Fragment erreicht
            bool frame_complete = false;
            if (current_frame_size > 0 && (hdr.offset + payload_len >= current_frame_size)) {
                frame_complete = true;
                ALOGV("=== FRAME COMPLETE: seq=%u, total_size=%zu ===", hdr.sequence, frame_buffer_used);

                // Kompletten JPEG Frame speichern
                save_jpeg_to_file(frame_buffer, frame_buffer_used, hdr.sequence, hdr.offset, true);
            }

            if (new_frame) {
                if (frames_sent > 0) {
                    rtp_timestamp += (RTP_CLOCK_RATE / assumed_fps);
                }
                frames_sent++;
                current_frame_size = hdr.frame_size;
                ALOGV("Processing frame: seq=%u frame_size=%u payload=%zu", hdr.sequence, current_frame_size, payload_len);

                if (use_rfc2435) {
                    // attempt to parse JPEG dimensions from payload (we are at offset==0 => start of JPEG)
                    if (parse_jpeg_size(payload_ptr, payload_len, &img_width, &img_height) == 0) {
                        ALOGV("jpg size parsed: %dx%d", img_width, img_height);
                    } else {
                        ALOGW("jpg size parse failed (will set 0/0)");
                        img_width = 0;
                        img_height = 0;
                    }
                }
            }

            uint16_t rtp_seq = use_proprietary_seq_as_rtp ? (uint16_t)(hdr.sequence & 0xffff) : (uint16_t)rand();

            // Marker-Strategie:
            bool marker = false;
            if (current_frame_size > 0 && (hdr.offset + payload_len >= current_frame_size)) {
                // Fall 2: Berechnung basierend auf offset + payload
                marker = true;
                ALOGD("MARKER: Calculated from offset (%u) + payload (%zu) >= frame_size (%u)",
                      hdr.offset, payload_len, current_frame_size);
            }

            // RTP Header bauen
            build_rtp_header(send_buf, RTP_PAYLOAD_TYPE_MJPEG, marker, rtp_seq, rtp_timestamp, ssrc);
            size_t total_send_len = RTP_HEADER_SIZE;

            if (use_rfc2435) {
                // RFC2435 JPEG Header
                uint8_t jpeg_hdr[8] = {0};
                jpeg_hdr[1] = (uint8_t)((hdr.offset >> 16) & 0xFF);
                jpeg_hdr[2] = (uint8_t)((hdr.offset >> 8) & 0xFF);
                jpeg_hdr[3] = (uint8_t)(hdr.offset & 0xFF);
                jpeg_hdr[4] = 1;  // JPEG baseline
                jpeg_hdr[5] = 255; // Q factor
                jpeg_hdr[6] = (uint8_t)((img_width + 7) / 8);
                jpeg_hdr[7] = (uint8_t)((img_height + 7) / 8);

                memcpy(send_buf + total_send_len, jpeg_hdr, sizeof(jpeg_hdr));
                total_send_len += sizeof(jpeg_hdr);
            }

            // Nutzdaten kopieren
            if (total_send_len + payload_len > sizeof(send_buf)) {
                ALOGE("Send buffer overflow: need %zu, have %zu",
                      total_send_len + payload_len, sizeof(send_buf));
                processed += PROTO_HEADER_SIZE + payload_len;
                continue;
            }

            memcpy(send_buf + total_send_len, payload_ptr, payload_len);
            total_send_len += payload_len;

            // Senden
            ssize_t sent = sendto(send_fd, send_buf, total_send_len, 0,
                                  (struct sockaddr *)&send_addr, sizeof(send_addr));

            if (sent < 0) {
                ALOGE("sendto error: %s", strerror(errno));
            } else {
                ALOGD("Sent RTP fragment: %zd bytes (seq: %u, offset: %u, marker: %d, combined_packet=%s)",
                      sent, hdr.sequence, hdr.offset, marker, is_combined_packet ? "YES" : "NO");
            }

            last_frame_sequence = hdr.sequence;

            // Zum nächsten Fragment
            processed += PROTO_HEADER_SIZE + payload_len;
        }

        // NEU: Zusammenfassung für kombinierte Pakete
        if (is_combined_packet && fragment_count > 1) {
            ALOGI("=== COMBINED PACKET SUMMARY: %d fragments processed from %zd bytes ===",
                  fragment_count, rlen);
        }

        // Frame-Zähler für Dateinamen erhöhen
        frame_counter++;
    }

    ALOGI("worker exiting");
    return NULL;
}

extern "C" {
// JNI: start(destIp, destPort, useRfc2435, fps)
JNIEXPORT void JNICALL
Java_local_test_camtest_protocol_RtpConvertProxy_start(JNIEnv *env, jobject thiz, jstring destIp,
                                                       jint destPort, jboolean juseRfc2435,
                                                       jint jfps) {
    if (running) {
        ALOGI("already running");
        return;
    }
    const char *ip = env->GetStringUTFChars(destIp, nullptr);
    use_rfc2435 = (juseRfc2435 == JNI_TRUE);
    if (jfps > 0) assumed_fps = jfps;

    recv_fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (recv_fd < 0) {
        ALOGE("socket recv failed: %s", strerror(errno));
        env->ReleaseStringUTFChars(destIp, ip);
        return;
    }
    struct sockaddr_in raddr;
    memset(&raddr, 0, sizeof(raddr));
    raddr.sin_family = AF_INET;
    raddr.sin_addr.s_addr = htonl(INADDR_ANY);
    raddr.sin_port = htons(PROTO_PORT);
    if (bind(recv_fd, (struct sockaddr *) &raddr, sizeof(raddr)) < 0) {
        ALOGE("bind failed: %s", strerror(errno));
        close(recv_fd);
        recv_fd = -1;
        env->ReleaseStringUTFChars(destIp, ip);
        return;
    }

    send_fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (send_fd < 0) {
        ALOGE("socket send failed: %s", strerror(errno));
        close(recv_fd);
        recv_fd = -1;
        env->ReleaseStringUTFChars(destIp, ip);
        return;
    }
    memset(&send_addr, 0, sizeof(send_addr));
    send_addr.sin_family = AF_INET;
    send_addr.sin_port = htons((int) destPort);
    if (inet_pton(AF_INET, ip, &send_addr.sin_addr) != 1) {
        ALOGE("inet_pton failed for %s", ip);
        close(recv_fd);
        close(send_fd);
        recv_fd = -1;
        send_fd = -1;
        env->ReleaseStringUTFChars(destIp, ip);
        return;
    }
    env->ReleaseStringUTFChars(destIp, ip);

    srand((unsigned) time(NULL));
    running = true;
    if (pthread_create(&worker_thread, NULL, worker_func, NULL) != 0) {
        ALOGE("pthread_create failed");
        running = false;
        close(recv_fd);
        close(send_fd);
        recv_fd = -1;
        send_fd = -1;
        return;
    }
    save_debug_image=true;
    ALOGI("RtpConvertProxy started -> forwarding to dest:%d rfc2435=%d fps=%d", (int) destPort,
          use_rfc2435 ? 1 : 0, assumed_fps);
}

JNIEXPORT void JNICALL
Java_local_test_camtest_protocol_RtpConvertProxy_stop(JNIEnv *env, jobject thiz) {
    if (!running) {
        ALOGI("not running");
        return;
    }
    save_debug_image=false;
    running = false;
    shutdown(recv_fd, SHUT_RDWR);
    pthread_join(worker_thread, NULL);
    if (recv_fd >= 0) close(recv_fd);
    if (send_fd >= 0) close(send_fd);
    recv_fd = -1;
    send_fd = -1;
    ALOGI("RtpConvertProxy stopped");
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    JNIEnv *env;
    if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    ALOGI("RtpConvertProxy JNI_OnLoad");
    return JNI_VERSION_1_6;
}
}