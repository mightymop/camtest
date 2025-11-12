#include "native-lib.h"
#include <iostream>
#include <thread>
#include <atomic>
#include <sys/socket.h>
#include <sys/types.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <vector>
#include <mutex>
#include <condition_variable>
#include <android/log.h>

#define LOG_TAG "NativeConnection"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Globale Variablen für den Stream
extern "C" {
std::atomic<bool> stream_running{false};

std::thread stream_thread;
int udp_socket = -1;
int forward_socket = -1;

struct sockaddr_in forward_addr;

bool is_last_jpeg_packet(const uint8_t *data, size_t length) {
    if (length < 22) return false; // Mindestens Header + 2 Bytes Payload

    // Suche rückwärts nach EOI für bessere Performance
    const uint8_t *payload_end = data + length;
    const uint8_t *payload_start = data + 20;

    for (const uint8_t *p = payload_end - 2; p >= payload_start; p--) {
        if (p[0] == 0xFF && p[1] == 0xD9) {
            LOGD("EOI marker found at position %ld", p - data);
            return true;
        }
    }
    return false;
}


std::vector<uint8_t> convert_to_standard_rtp_with_params(const uint8_t *data, size_t length,
                                                         uint8_t payload_type = 26,
                                                         uint16_t width = 640,
                                                         uint16_t height = 480) {
    if (length < 20) {
        LOGW("Packet too short for conversion: %zu bytes", length);
        return {};
    }

    static uint32_t packet_counter = 0;
    packet_counter++;

    // --- Proprietary Header ---
    uint8_t type = data[0];
    uint8_t reserved = data[1];
    uint16_t blockSize = (data[2] << 8) | data[3];
    uint32_t sequence = (data[4] << 24) | (data[5] << 16) | (data[6] << 8) | data[7];
    uint32_t frametick = (data[8] << 24) | (data[9] << 16) | (data[10] << 8) | data[11];
    uint32_t offset = (data[12] << 24) | (data[13] << 16) | (data[14] << 8) | data[15];
    uint32_t reserved2 = (data[16] << 24) | (data[17] << 16) | (data[18] << 8) | data[19];

    size_t jpeg_len = length - 20;

    bool is_last_packet = is_last_jpeg_packet(data, length);

    /*
       bool has_jpeg = jpeg_len > 0;
       LOGD("# %5u | %5zu bytes ||| %02X | %02X | %02X %02X | %08X | %08X | %08X | %08X ||| %s",
          packet_counter, length,
          type,
          reserved,
          (blockSize >> 8) & 0xFF, blockSize & 0xFF,
          sequence,
          frametick,
          offset,
          reserved2,
          has_jpeg ? (is_last_packet ? "LAST" : "YES") : "NO"
     );

     */
    // ----------------------------------------------------------
    // Standard RTP Construction begins here...
    // ----------------------------------------------------------

    std::vector<uint8_t> converted_data;
    converted_data.reserve(12 + 8 + jpeg_len);

    // === RTP Header ===
    converted_data.push_back(0x80);
    uint8_t byte1 = payload_type & 0x7F;
    if (is_last_packet) byte1 |= 0x80;
    converted_data.push_back(byte1);

    uint16_t rtp_sequence = sequence & 0xFFFF;
    converted_data.push_back((rtp_sequence >> 8) & 0xFF);
    converted_data.push_back(rtp_sequence & 0xFF);

    converted_data.push_back((frametick >> 24) & 0xFF);
    converted_data.push_back((frametick >> 16) & 0xFF);
    converted_data.push_back((frametick >> 8) & 0xFF);
    converted_data.push_back(frametick & 0xFF);

    static const uint32_t ssrc = 0x12345678;
    converted_data.push_back((ssrc >> 24) & 0xFF);
    converted_data.push_back((ssrc >> 16) & 0xFF);
    converted_data.push_back((ssrc >> 8) & 0xFF);
    converted_data.push_back(ssrc & 0xFF);

    // === RFC2435 JPEG Header ===
    uint32_t fragment_offset_quantums = offset / 8;
    converted_data.push_back(0x00);
    converted_data.push_back(0x00);
    converted_data.push_back((fragment_offset_quantums >> 8) & 0xFF);
    converted_data.push_back(fragment_offset_quantums & 0xFF);
    converted_data.push_back(0x01);
    converted_data.push_back(0x00);
    converted_data.push_back((width / 8) & 0xFF);
    converted_data.push_back((height / 8) & 0xFF);

    // === JPEG Payload ===
    converted_data.insert(converted_data.end(), data + 20, data + length);

    return converted_data;
}


void stream_worker(int width, int height) {
    const int BUFFER_SIZE = 65536; // Sinnvollere Größe
    uint8_t buffer[BUFFER_SIZE];

    LOGI("Stream worker started with resolution: %dx%d", width, height);

    while (stream_running) {
        // ✅ KORREKT: sockaddr für IPv4 verwenden
        struct sockaddr client_addr;
        socklen_t client_len = sizeof(client_addr);

        // ✅ WICHTIG: Struktur zurücksetzen
        memset(&client_addr, 0, sizeof(client_addr));

        ssize_t received = recvfrom(udp_socket, buffer, BUFFER_SIZE, 0,
                                    (struct sockaddr *) &client_addr, &client_len);

        if (received > 0) {
            // LOGD("Received packet: %zd bytes", received);

            if (received >= 20) {
                // Header-Validierung
                if (buffer[0] != 0x02 || buffer[1] != 0x00) {
                    LOGW("Ungültiger Header: %02X %02X", buffer[0], buffer[1]);
                    continue;
                }

                auto converted_data = convert_to_standard_rtp_with_params(buffer, received, 26,
                                                                          width, height);

                if (!converted_data.empty()) {
                    // UDP-Mode: An localhost:6666 weiterleiten
                    ssize_t sent = sendto(forward_socket, converted_data.data(),
                                          converted_data.size(), 0,
                                          (struct sockaddr *) &forward_addr,
                                          sizeof(forward_addr));
                    /*if (sent > 0) {
                        LOGD("Forwarded %zd bytes to :6666", sent);
                    } else {
                        LOGW("Failed to forward packet, error: %s", strerror(errno));
                    }*/
                }

            } else {
                LOGW("Packet too short: %zd bytes", received);
            }
        } else if (received < 0) {
            if (errno != EAGAIN && errno != EWOULDBLOCK) {
                LOGE("recvfrom error: %s", strerror(errno));
                // Bei schweren Fehlern kurz warten
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
            }
        }
        // received == 0 ist normal (keine Daten)
    }

    LOGI("Stream worker stopped");
}

JNIEXPORT jboolean JNICALL
Java_local_test_camtest_protocol_NativeConnection_start(JNIEnv *env, jobject thiz, jstring ip_jstr,
                                                        jint width,
                                                        jint height) {

    const char *ip = env->GetStringUTFChars(ip_jstr, nullptr);
    if (!ip) {
        LOGE("Invalid IP string");
        return JNI_FALSE;
    }

    LOGI("Starting stream with resolution: %dx%d on IP: %s", width, height, ip);

    if (stream_running) {
        env->ReleaseStringUTFChars(ip_jstr, ip);
        return JNI_TRUE;
    }

    // UDP Socket für Empfang erstellen
    udp_socket = socket(AF_INET, SOCK_DGRAM, 0);
    if (udp_socket < 0) {
        LOGE("Failed to create receive socket: %s", strerror(errno));
        env->ReleaseStringUTFChars(ip_jstr, ip);
        return JNI_FALSE;
    }
    LOGI("Receive socket created: %d", udp_socket);

    // Socket für Weiterleitung erstellen
    forward_socket = socket(AF_INET, SOCK_DGRAM, 0);
    if (forward_socket < 0) {
        LOGE("Failed to create forward socket: %s", strerror(errno));
        close(udp_socket);
        env->ReleaseStringUTFChars(ip_jstr, ip);
        return JNI_FALSE;
    }
    LOGI("Forward socket created: %d", forward_socket);

    // Empfangs-Socket binden
    struct sockaddr_in server_addr;
    memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sin_family = AF_INET;
    if (inet_pton(AF_INET, ip, &server_addr.sin_addr) != 1) {
        LOGE("Invalid IP address: %s", ip);
        close(udp_socket);
        close(forward_socket);
        env->ReleaseStringUTFChars(ip_jstr, ip);
        return JNI_FALSE;
    }
    server_addr.sin_port = htons(2224);

    if (bind(udp_socket, (struct sockaddr *) &server_addr, sizeof(server_addr)) < 0) {
        LOGE("Failed to bind receive socket: %s", strerror(errno));
        close(udp_socket);
        close(forward_socket);
        env->ReleaseStringUTFChars(ip_jstr, ip);
        return JNI_FALSE;
    }
    LOGI("Receive socket bound to %s:2224", ip);

    // Forward-Adresse setzen
    memset(&forward_addr, 0, sizeof(forward_addr));
    forward_addr.sin_family = AF_INET;
    if (inet_pton(AF_INET, ip, &forward_addr.sin_addr) != 1) {
        LOGE("Invalid forward IP address: %s", ip);
        close(udp_socket);
        close(forward_socket);
        env->ReleaseStringUTFChars(ip_jstr, ip);
        return JNI_FALSE;
    }
    forward_addr.sin_port = htons(6666);

    LOGI("Forward address set to %s:6666", ip);

    env->ReleaseStringUTFChars(ip_jstr, ip);

    stream_running = true;
    stream_thread = std::thread([width, height]() {
        stream_worker(width, height);
    });

    LOGI("Stream started successfully with resolution %dx%d", width, height);
    return JNI_TRUE;
}


JNIEXPORT void JNICALL
Java_local_test_camtest_protocol_NativeConnection_stop(JNIEnv *env, jobject thiz) {

    LOGI("Stopping stream...");
    stream_running = false;

    if (stream_thread.joinable()) {
        LOGI("Waiting for stream thread to finish...");
        stream_thread.join();
        LOGI("Stream thread finished");
    }

    if (udp_socket >= 0) {
        close(udp_socket);
        udp_socket = -1;
        LOGI("Receive socket closed");
    }

    if (forward_socket >= 0) {
        close(forward_socket);
        forward_socket = -1;
        LOGI("Forward socket closed");
    }

    LOGI("Stream stopped successfully");
}


}