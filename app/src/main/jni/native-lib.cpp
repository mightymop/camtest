// rtp_fixer_rfc2435_native.c
// NATIVE VERSION: libRtpConvertProxy.so
#include <jni.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <errno.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <queue>
#include <mutex>
#include <condition_variable>

#define LOG_TAG "RtpConvertProxy"
#define ALOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define PROTO_HEADER_SIZE 20
#define MAX_UDP_PAYLOAD 65507

// ------------ GLOBALS ------------
static JavaVM* gJavaVM = nullptr;
static ANativeWindow* native_window = nullptr;
static volatile bool running = false;
static pthread_t worker_thread;
static int recv_fd = -1;

static uint32_t frame_counter = 0;

// ------------ HELPERS ------------
static inline uint16_t le16(const uint8_t* p) {
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}

static inline uint32_t le32(const uint8_t* p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

struct proto_hdr {
    uint8_t type;
    uint8_t reserved1;
    uint16_t blocksize;
    uint32_t sequence;
    uint32_t frame_size;
    uint32_t offset;
    uint32_t timestamp;
};

static std::queue<std::vector<uint8_t>> jpeg_queue;
static std::mutex queue_mutex;
static std::condition_variable queue_cv;

// Füge Frame zur Queue hinzu
static void enqueue_jpeg(const uint8_t* data, size_t size) {
    std::vector<uint8_t> frame(data, data + size);
    {
        std::lock_guard<std::mutex> lock(queue_mutex);
        jpeg_queue.push(std::move(frame));
    }
    queue_cv.notify_one();
}


// ------------ WORKER THREAD ------------
static void* worker_func(void* arg) {
    uint8_t recv_buf[MAX_UDP_PAYLOAD];
    uint8_t* frame_buf = nullptr;
    size_t frame_buf_size = 0;
    size_t frame_used = 0;
    uint32_t expected_size = 0;

    struct sockaddr_in caddr;
    socklen_t clen = sizeof(caddr);

    while (running) {
        ssize_t r = recvfrom(recv_fd, recv_buf, sizeof(recv_buf), 0,
                             (struct sockaddr*)&caddr, &clen);
        if (r <= 0) {
            if (errno == EINTR) continue;
            if (!running) break;
            ALOGE("recvfrom failed: %s", strerror(errno));
            continue;
        }

        size_t off = 0;
        while (off + PROTO_HEADER_SIZE <= (size_t)r) {
            struct proto_hdr h;
            h.type       = recv_buf[off + 0];
            h.blocksize  = le16(recv_buf + off + 2);
            h.sequence   = le32(recv_buf + off + 4);
            h.frame_size = le32(recv_buf + off + 8);
            h.offset     = le32(recv_buf + off + 12);

            if (off + PROTO_HEADER_SIZE + h.blocksize > (size_t)r) break;

            bool last = (h.type & 0x80) != 0;
            uint8_t dtype = h.type & 0x7F;
            if (dtype != 2) {
                off += PROTO_HEADER_SIZE + h.blocksize;
                continue;
            }

            // Allocate frame buffer if needed
            if (!frame_buf || expected_size != h.frame_size) {
                free(frame_buf);
                expected_size = h.frame_size;
                frame_buf_size = expected_size;
                frame_buf = (uint8_t*)malloc(frame_buf_size);
                frame_used = 0;
            }

            // Copy fragment
            if (h.offset + h.blocksize <= frame_buf_size) {
                memcpy(frame_buf + h.offset, recv_buf + off + PROTO_HEADER_SIZE, h.blocksize);
                frame_used = (h.offset + h.blocksize > frame_used) ? h.offset + h.blocksize : frame_used;
            } else {
                ALOGE("Frame buffer overflow detected! Fragment skipped");
            }

            if (last || frame_used >= expected_size) {
                enqueue_jpeg(frame_buf, frame_used);  // Nur Queue füllen
                frame_counter++;
                frame_used = 0;
                expected_size = 0;
            }

            off += PROTO_HEADER_SIZE + h.blocksize;
        }
    }

    free(frame_buf);
    return nullptr;
}

// ------------ JNI INTERFACE ------------
jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    gJavaVM = vm;
    return JNI_VERSION_1_6;
}

// Hole Frame (Main/UI Thread)
extern "C" JNIEXPORT jbyteArray JNICALL
Java_de_mopsdom_rearview_protocol_RtpConvertProxy_getNextFrame(
        JNIEnv* env, jobject thiz) {

    std::unique_lock<std::mutex> lock(queue_mutex);
    if (jpeg_queue.empty()) return nullptr;

    auto frame = std::move(jpeg_queue.front());
    jpeg_queue.pop();
    lock.unlock();

    jbyteArray arr = env->NewByteArray(frame.size());
    env->SetByteArrayRegion(arr, 0, frame.size(), (jbyte*)frame.data());
    return arr;
}

extern "C" {

JNIEXPORT void JNICALL
Java_de_mopsdom_rearview_protocol_RtpConvertProxy_setSurface(
        JNIEnv* env, jobject thiz, jobject surface) {
    if (native_window) {
        ANativeWindow_release(native_window);
        native_window = nullptr;
    }
    if (surface) {
        native_window = ANativeWindow_fromSurface(env, surface);
        ALOGI("NativeWindow set");
    }
}

JNIEXPORT jboolean JNICALL
Java_de_mopsdom_rearview_protocol_RtpConvertProxy_start(
        JNIEnv* env, jobject thiz, jint port) {

    if (running) return JNI_TRUE;

    recv_fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (recv_fd < 0) return JNI_FALSE;

    struct sockaddr_in a;
    memset(&a, 0, sizeof(a));
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_ANY);
    a.sin_port = htons(port);

    if (bind(recv_fd, (struct sockaddr*)&a, sizeof(a)) < 0) {
        close(recv_fd);
        return JNI_FALSE;
    }

    running = true;
    pthread_create(&worker_thread, nullptr, worker_func, nullptr);

    ALOGI("RTP Converter started on port %d", port);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_de_mopsdom_rearview_protocol_RtpConvertProxy_stop(
        JNIEnv* env, jobject thiz) {

    if (!running) return;

    running = false;
    shutdown(recv_fd, SHUT_RDWR);
    pthread_join(worker_thread, nullptr);

    if (recv_fd >= 0) close(recv_fd);
    recv_fd = -1;

    if (native_window) {
        ANativeWindow_release(native_window);
        native_window = nullptr;
    }

    ALOGI("RTP Converter stopped");
}

}
