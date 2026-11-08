/* Modifications Copyright (C) 2026 Ettacent */

#include <jni.h>
#include <android/bitmap.h>
#include <cstdint>
#include <atomic>
#include <algorithm>
#include <cerrno>
#include <limits>
#include <memory>
#include <new>
#include <string>
#include <unistd.h>
#include <sys/stat.h>
#include <linux/stat.h>
#include <asm/fcntl.h>
#include <fcntl.h>
#include <libyuv.h>
#include <tgnet/FileLog.h>
#include "tgnet/ConnectionsManager.h"
#include "voip/webrtc/common_video/h264/sps_parser.h"
#include "voip/webrtc/common_video/h264/h264_common.h"
#include "c_utils.h"
#include "gifvideo/sws_context_holder.h"
#include "gifvideo/video_frame_reader.h"
#include <cmath>

extern "C" {
#include <libavformat/avformat.h>
#include <libavutil/eval.h>
#include <libswscale/swscale.h>
#include <libavutil/display.h>
}

#define RGB8888_A(p) ((p & (0xff<<24))      >> 24 )

static const std::string av_make_error_str(int errnum) {
    char errbuf[AV_ERROR_MAX_STRING_SIZE];
    av_strerror(errnum, errbuf, AV_ERROR_MAX_STRING_SIZE);
    return (std::string) errbuf;
}

#undef av_err2str
#define av_err2str(errnum) av_make_error_str(errnum).c_str()
#define FFMPEG_AVSEEK_SIZE 0x10000

jclass jclass_AnimatedFileDrawableStream;
jmethodID jclass_AnimatedFileDrawableStream_read;
jmethodID jclass_AnimatedFileDrawableStream_cancel;
jmethodID jclass_AnimatedFileDrawableStream_isCanceled;
jmethodID jclass_AnimatedFileDrawableStream_isFinishedLoadingFile;
jmethodID jclass_AnimatedFileDrawableStream_getFinishedFilePath;

struct OffsetIOContext;
struct VideoInfo;
static void freeOffsetIO(VideoInfo *info);
static bool isStreamCanceled(VideoInfo *info);

struct DecoderJniEnv {
    JNIEnv *env = nullptr;
    bool attached = false;

    DecoderJniEnv() {
        int status = javaVm->GetEnv((void **) &env, JNI_VERSION_1_6);
        if (status == JNI_EDETACHED) {
            JavaVMAttachArgs args{};
            args.version = JNI_VERSION_1_6;
            if (javaVm->AttachCurrentThread(&env, &args) == JNI_OK) attached = true;
            else env = nullptr;
        } else if (status != JNI_OK) env = nullptr;
    }

    ~DecoderJniEnv() { if (attached) javaVm->DetachCurrentThread(); }
};

typedef struct VideoInfo {

    ~VideoInfo() {
        delete reader;
        reader = nullptr;

        if (video_dec_ctx) {
            // avcodec_close() frees internals but NOT the context allocated by
            // avcodec_alloc_context3(); avcodec_free_context() frees both.
            avcodec_free_context(&video_dec_ctx);
        }
        if (fmt_ctx) {
            avformat_close_input(&fmt_ctx);
            fmt_ctx = nullptr;
        }
        if (src) {
            delete [] src;
            src = nullptr;
        }
        if (stream != nullptr) {
            DecoderJniEnv access;
            DEBUG_DELREF("gifvideo.cpp stream");
            if (access.env != nullptr) access.env->DeleteGlobalRef(stream);
            else LOGE("JNI environment unavailable for decoder reference cleanup");
            stream = nullptr;
        }
        if (ioContext != nullptr) {
            if (ioContext->buffer) {
                av_freep(&ioContext->buffer);
            }
            avio_context_free(&ioContext);
            ioContext = nullptr;
        }
        freeOffsetIO(this);
        if (fd >= 0) {
            if (close(fd) != 0) LOGE("decoder fd close failed: %d", errno);
            fd = -1;
        }

        video_stream_idx = -1;
        video_stream = nullptr;
        audio_stream = nullptr;
    }

    AVFormatContext *fmt_ctx = nullptr;
    char *src = nullptr;
    int video_stream_idx = -1;
    AVStream *video_stream = nullptr;
    AVStream *audio_stream = nullptr;
    AVCodecContext *video_dec_ctx = nullptr;
    // Borrows fmt_ctx and video_dec_ctx; must be deleted before them (see dtor).
    VideoFrameReader *reader = nullptr;
    std::atomic<bool> stopped{false};
    std::atomic<bool> seeking{false};
    bool afterEof = false;
    bool isSingleFrame = false;

    struct SwsContextHolder sws_ctx_holder;

    AVIOContext *ioContext = nullptr;
    unsigned char *ioBuffer = nullptr;
    // Custom AVIO for the fileOffset path in nGetVideoInfo. Owned here because
    // fmt_ctx->pb is not freed by avformat_close_input for a caller-supplied pb.
    AVIOContext *offsetIoContext = nullptr;
    struct OffsetIOContext *offsetIoOpaque = nullptr;
    jobject stream = nullptr;
    int32_t account = 0;
    int fd = -1;
    int64_t file_size = 0;
    int64_t last_seek_p = 0;
    bool timed = false;
    bool snapshot = false;
    std::atomic<int64_t> cancelIntent{1};
    int64_t activeIntent = 1;
    int64_t nextFrameId = 0;
    int64_t pendingId = 0;
    int64_t lastResolvedId = 0;
    int lastDisposition = 0;
    bool materialized = false;
    bool readFailed = false;
    bool protocolFailed = false;
};

enum TimedOutcome {
    TIMED_ACQUIRED = 1, TIMED_EOF = 2, TIMED_AGAIN = 3, TIMED_YIELD = 4,
    TIMED_CANCELLED = 5, TIMED_WRITE_ERROR = 6, TIMED_READ_ERROR = 7,
    TIMED_PROTOCOL_ERROR = 8, TIMED_MATERIALIZED = 9, TIMED_COMMITTED = 10,
    TIMED_DISCARDED = 11, TIMED_SEEKED = 12
};

static bool timedCancelled(VideoInfo *info) {
    return info->protocolFailed || info->cancelIntent.load() != info->activeIntent || isStreamCanceled(info);
}

static int timedInterrupt(void *opaque) {
    auto *info = static_cast<VideoInfo *>(opaque);
    return info->cancelIntent.load() != info->activeIntent;
}

void custom_log(void *ptr, int level, const char* fmt, va_list vl){
    va_list vl2;
    char line[1024];
    static int print_prefix = 1;

    va_copy(vl2, vl);
    av_log_format_line(ptr, level, fmt, vl2, line, sizeof(line), &print_prefix);
    va_end(vl2);

    LOGE(line);
}

static enum AVPixelFormat get_format(AVCodecContext *ctx,
                                        const enum AVPixelFormat *pix_fmts)
{
    const enum AVPixelFormat *p;

    for (p = pix_fmts; *p != -1; p++) {
        LOGE("available format %d", p);
    }

    return pix_fmts[0];
}

int open_codec_context(int *stream_idx, AVCodecContext **dec_ctx, AVFormatContext *fmt_ctx, enum AVMediaType type) {
    int ret, stream_index;
    AVStream *st;
    const AVCodec *dec = NULL;
    AVDictionary *opts = NULL;

    ret = av_find_best_stream(fmt_ctx, type, -1, -1, NULL, 0);
    if (ret < 0) {
        LOGE("can't find %s stream in input file", av_get_media_type_string(type));
        return ret;
    } else {
        stream_index = ret;
        st = fmt_ctx->streams[stream_index];

        dec = avcodec_find_decoder(st->codecpar->codec_id);
        if (!dec) {
            LOGE("failed to find %d codec", st->codecpar->codec_id);
            return AVERROR(EINVAL);
        }

        *dec_ctx = avcodec_alloc_context3(dec);
        if (!*dec_ctx) {
            LOGE("Failed to allocate the %s codec context", av_get_media_type_string(type));
            return AVERROR(ENOMEM);
        }

        if ((ret = avcodec_parameters_to_context(*dec_ctx, st->codecpar)) < 0) {
            LOGE("Failed to copy %s codec parameters to decoder context", av_get_media_type_string(type));
            return ret;
        }

        if (st->time_base.num > 0 && st->time_base.den > 0) {
            (*dec_ctx)->pkt_timebase = st->time_base;
        }

        ret = av_dict_set(&opts, "refcounted_frames", "1", 0);
        if (ret < 0) { av_dict_free(&opts); return ret; }
        ret = avcodec_open2(*dec_ctx, dec, &opts);
        av_dict_free(&opts);   // frees leftover (unconsumed) options on all paths
        if (ret < 0) {
            LOGE("Failed to open %s codec", av_get_media_type_string(type));
            return ret;
        }
        *stream_idx = stream_index;
    }

    return 0;
}

void requestFd(VideoInfo *info) {
    if (info->protocolFailed) return;
    DecoderJniEnv access;
    JNIEnv *jniEnv = access.env;
    if (jniEnv == nullptr || jniEnv->ExceptionCheck() || info->stream == nullptr) {
        info->protocolFailed = true;
        return;
    }
    jniEnv->CallIntMethod(info->stream, jclass_AnimatedFileDrawableStream_read, (jint) 0, (jint) 1);
    if (jniEnv->ExceptionCheck()) { info->protocolFailed = true; return; }
    jboolean loaded = jniEnv->CallBooleanMethod(info->stream, jclass_AnimatedFileDrawableStream_isFinishedLoadingFile);
    if (jniEnv->ExceptionCheck()) { info->protocolFailed = true; return; }
    if (loaded) {
        jstring src = (jstring) jniEnv->CallObjectMethod(info->stream, jclass_AnimatedFileDrawableStream_getFinishedFilePath);
        if (jniEnv->ExceptionCheck() || src == nullptr) {
            if (src != nullptr) jniEnv->DeleteLocalRef(src);
            info->protocolFailed = true;
            return;
        }
        char const *srcString = jniEnv->GetStringUTFChars(src, 0);
        if (jniEnv->ExceptionCheck() || srcString == nullptr) {
            if (srcString != nullptr) jniEnv->ReleaseStringUTFChars(src, srcString);
            jniEnv->DeleteLocalRef(src);
            info->protocolFailed = true;
            return;
        }
        size_t len = strlen(srcString);
        char *replacement = len > 0 ? new (std::nothrow) char[len + 1] : nullptr;
        if (replacement != nullptr) {
            memcpy(replacement, srcString, len);
            replacement[len] = '\0';
        }
        jniEnv->ReleaseStringUTFChars(src, srcString);
        jniEnv->DeleteLocalRef(src);
        if (replacement == nullptr || jniEnv->ExceptionCheck()) {
            delete[] replacement;
            info->protocolFailed = true;
            return;
        }
        char *previous = info->src;
        info->src = replacement;
        delete[] previous;
    }
    if (info->src == nullptr || info->src[0] == '\0') {
        info->protocolFailed = true;
        return;
    }
    info->fd = open(info->src, O_RDONLY, S_IRUSR);
}

int readCallback(void *opaque, uint8_t *buf, int buf_size) {
    VideoInfo *info = (VideoInfo *) opaque;
    if (info->timed) {
        if (timedCancelled(info)) return AVERROR_EXIT;
        if (buf_size <= 0 || info->last_seek_p < 0) return AVERROR(EINVAL);
        if (info->last_seek_p >= info->file_size) return AVERROR_EOF;
        if (info->fd < 0 && !info->snapshot) requestFd(info);
        if (timedCancelled(info)) return AVERROR_EXIT;
        if (info->fd < 0) return AVERROR(EIO);
        int wanted = static_cast<int>(std::min<int64_t>(buf_size, info->file_size - info->last_seek_p));
        if (info->stream != nullptr) {
            DecoderJniEnv access;
            JNIEnv *jniEnv = access.env;
            if (jniEnv == nullptr || jniEnv->ExceptionCheck()) {
                info->protocolFailed = true;
                return AVERROR(EIO);
            }
            int available = jniEnv->CallIntMethod(info->stream, jclass_AnimatedFileDrawableStream_read,
                    static_cast<jint>(info->last_seek_p), static_cast<jint>(wanted));
            bool failed = jniEnv->ExceptionCheck();
            if (failed) { info->protocolFailed = true; return AVERROR(EIO); }
            if (timedCancelled(info)) return AVERROR_EXIT;
            if (available <= 0 || available > wanted) return AVERROR(EIO);
            wanted = available;
        }
        static_assert(sizeof(off64_t) >= 8, "snapshot offsets require 64 bits");
        ssize_t got = pread64(info->fd, buf, static_cast<size_t>(wanted), static_cast<off64_t>(info->last_seek_p));
        const int readError = errno;
        if (timedCancelled(info)) return AVERROR_EXIT;
        if (got < 0) return AVERROR(readError == EINTR ? EAGAIN : readError);
        if (got == 0) return AVERROR(EIO);
        info->last_seek_p += got;
        return static_cast<int>(got);
    }
    if (!info->stopped) {
        if (info->fd < 0) {
            requestFd(info);
        }
        if (info->fd >= 0) {
            if (info->last_seek_p + buf_size > info->file_size) {
                buf_size = (int) (info->file_size - info->last_seek_p);
            }
            if (buf_size > 0) {
                JNIEnv *jniEnv = nullptr;

                JavaVMAttachArgs jvmArgs;
                jvmArgs.version = JNI_VERSION_1_6;

                bool attached;
                if (JNI_EDETACHED == javaVm->GetEnv((void **) &jniEnv, JNI_VERSION_1_6)) {
                    javaVm->AttachCurrentThread(&jniEnv, &jvmArgs);
                    attached = true;
                } else {
                    attached = false;
                }

                buf_size = jniEnv->CallIntMethod(info->stream, jclass_AnimatedFileDrawableStream_read, (jint) info->last_seek_p, (jint) buf_size);
                info->last_seek_p += buf_size;
                if (attached) {
                    javaVm->DetachCurrentThread();
                }
                if (buf_size == 0) {
                    return AVERROR_EXIT;
                }
                int ret = (int) read(info->fd, buf, (size_t) buf_size);
                if (ret <= 0) {
                    return AVERROR_EOF;
                }
                return ret;
            }
        }
    }
    return AVERROR_EOF;
}

int64_t seekCallback(void *opaque, int64_t offset, int whence) {
    VideoInfo *info = (VideoInfo *) opaque;
    if (info->timed) {
        if (timedCancelled(info)) return AVERROR_EXIT;
        if (whence & AVSEEK_SIZE) return info->file_size;
        whence &= ~AVSEEK_FORCE;
        int64_t base;
        if (whence == SEEK_SET) base = 0;
        else if (whence == SEEK_CUR) base = info->last_seek_p;
        else if (whence == SEEK_END) base = info->file_size;
        else return AVERROR(EINVAL);
        if (offset < -base || (offset > 0 && offset > INT64_MAX - base)) return AVERROR(EINVAL);
        int64_t position = base + offset;
        if (position > info->file_size) return AVERROR(EINVAL);
        info->last_seek_p = position;
        return position;
    }
    if (!info->stopped) {
        if (info->fd < 0) {
            requestFd(info);
        }
        if (info->fd >= 0) {
            if (whence & FFMPEG_AVSEEK_SIZE) {
                return info->file_size;
            } else {
                info->last_seek_p = offset;
                lseek(info->fd, off_t(offset), SEEK_SET);
                return offset;
            }
        }
    }
    return 0;
}

enum PARAM_NUM {
    PARAM_NUM_SUPPORTED_VIDEO_CODEC = 0,
    PARAM_NUM_WIDTH = 1,
    PARAM_NUM_HEIGHT = 2,
    PARAM_NUM_BITRATE = 3,
    PARAM_NUM_DURATION = 4,
    PARAM_NUM_AUDIO_FRAME_SIZE = 5,
    PARAM_NUM_VIDEO_FRAME_SIZE = 6,
    PARAM_NUM_FRAMERATE = 7,
    PARAM_NUM_ROTATION = 8,
    PARAM_NUM_SUPPORTED_AUDIO_CODEC = 9,
    PARAM_NUM_HAS_AUDIO = 10,
    PARAM_NUM_COUNT = 11,
};

struct OffsetIOContext {
    int fd;
    int64_t offset;
};

static int offsetRead(void *opaque, uint8_t *buf, int size) {
    OffsetIOContext *ctx = (OffsetIOContext *)opaque;
    if (ctx == nullptr || ctx->offset < 0 || buf == nullptr || size <= 0) return AVERROR(EINVAL);
    int64_t current = lseek64(ctx->fd, 0, SEEK_CUR);
    if (current < 0) return AVERROR(errno);
    if (current < ctx->offset) return AVERROR(EINVAL);
    ssize_t ret;
    do {
        ret = read(ctx->fd, buf, size);
    } while (ret < 0 && errno == EINTR);
    if (ret == 0) return AVERROR_EOF;
    return ret < 0 ? AVERROR(errno) : (int) ret;
}

static int64_t offsetSeek(void *opaque, int64_t pos, int whence) {
    OffsetIOContext *ctx = (OffsetIOContext *)opaque;
    if (ctx == nullptr || ctx->offset < 0) return AVERROR(EINVAL);
    whence &= ~AVSEEK_FORCE;
    int64_t base;
    switch (whence) {
        case AVSEEK_SIZE:
        case SEEK_END: {
            struct stat64 st;
            if (fstat64(ctx->fd, &st) < 0) return AVERROR(errno);
            if (st.st_size < ctx->offset) return AVERROR(EINVAL);
            base = st.st_size - ctx->offset;
            if (whence == AVSEEK_SIZE) return base;
            break;
        }
        case SEEK_CUR: {
            int64_t current = lseek64(ctx->fd, 0, SEEK_CUR);
            if (current < 0) return AVERROR(errno);
            if (current < ctx->offset) return AVERROR(EINVAL);
            base = current - ctx->offset;
            break;
        }
        case SEEK_SET:
            base = 0;
            break;
        default:
            return AVERROR(EINVAL);
    }
    if (pos < -base) return AVERROR(EINVAL);
    if (pos > INT64_MAX - base) return AVERROR(EOVERFLOW);
    int64_t target = base + pos;
    if (target > INT64_MAX - ctx->offset) return AVERROR(EOVERFLOW);
    int64_t ret = lseek64(ctx->fd, ctx->offset + target, SEEK_SET);
    return ret < 0 ? AVERROR(errno) : ret - ctx->offset;
}

// Frees the custom AVIO built for the fileOffset path (see nGetVideoInfo).
// Safe to call when nothing was allocated: all fields default to null/-1.
static void freeOffsetIO(VideoInfo *info) {
    if (info->offsetIoContext != nullptr) {
        av_freep(&info->offsetIoContext->buffer);
        avio_context_free(&info->offsetIoContext);
        info->offsetIoContext = nullptr;
    }
    if (info->offsetIoOpaque != nullptr) {
        if (info->offsetIoOpaque->fd >= 0) {
            close(info->offsetIoOpaque->fd);
        }
        delete info->offsetIoOpaque;
        info->offsetIoOpaque = nullptr;
    }
}

int getVideoRotation(const AVStream *stream) {
    const AVPacketSideData *displayMatrix = av_packet_side_data_get(
            stream->codecpar->coded_side_data,
            stream->codecpar->nb_coded_side_data,
            AV_PKT_DATA_DISPLAYMATRIX);
    if (displayMatrix != nullptr && displayMatrix->size >= 9 * sizeof(int32_t)) {
        // av_display_rotation_get() returns the counter-clockwise angle; negate
        // to match the legacy clockwise convention, then normalize.
        double theta = -av_display_rotation_get((const int32_t *) displayMatrix->data);
        if (!std::isnan(theta)) {
            int rotation = ((int) lround(theta / 90.0) * 90) % 360;
            return rotation < 0 ? rotation + 360 : rotation;
        }
        return 0;
    }

    // Fallback for old containers that still carry the legacy metadata tag.
    AVDictionaryEntry *rotate_tag = av_dict_get(stream->metadata, "rotate", nullptr, 0);
    if (rotate_tag && *rotate_tag->value && strcmp(rotate_tag->value, "0") != 0) {
        char *tail;
        int rotation = (int) av_strtod(rotate_tag->value, &tail);
        if (*tail == '\0') {
            rotation = ((rotation / 90) * 90) % 360;
            return rotation < 0 ? rotation + 360 : rotation;
        }
    }
    return 0;
}


extern "C" JNIEXPORT void JNICALL Java_org_telegram_ui_Components_AnimatedFileNative_nGetVideoInfo(JNIEnv *env, jclass clazz, jstring src, jintArray data, jlong fileOffset) {
    if (env == nullptr || env->ExceptionCheck() || src == nullptr || data == nullptr) return;
    jsize dataLength = env->GetArrayLength(data);
    if (env->ExceptionCheck() || dataLength < PARAM_NUM_COUNT) return;

    std::unique_ptr<VideoInfo> info(new (std::nothrow) VideoInfo());
    if (info == nullptr) return;

    char const *srcString = env->GetStringUTFChars(src, 0);
    if (env->ExceptionCheck() || srcString == nullptr) {
        if (srcString != nullptr) env->ReleaseStringUTFChars(src, srcString);
        return;
    }
    size_t len = strlen(srcString);
    if (len != std::numeric_limits<size_t>::max()) {
        info->src = new (std::nothrow) char[len + 1];
        if (info->src != nullptr) {
            memcpy(info->src, srcString, len);
            info->src[len] = '\0';
        }
    }
    env->ReleaseStringUTFChars(src, srcString);
    if (env->ExceptionCheck() || info->src == nullptr) return;

    try {
    int ret;
    if (fileOffset > 0) {
        info->offsetIoOpaque = new (std::nothrow) OffsetIOContext{-1, fileOffset};
        if (info->offsetIoOpaque == nullptr) return;
        int fd = open(info->src, O_RDONLY);
        info->offsetIoOpaque->fd = fd;
        if (fd < 0) {
            LOGE("can't open source file %s", info->src);
            return;
        }
        if (lseek64(fd, fileOffset, SEEK_SET) < 0) {
            LOGE("can't seek to offset %lld in file %s", (long long)fileOffset, info->src);
            return;
        }

        uint8_t *ioBuf = (uint8_t *)av_malloc(32 * 1024);
        if (ioBuf == nullptr) return;
        info->offsetIoContext = avio_alloc_context(ioBuf, 32 * 1024, 0, info->offsetIoOpaque, offsetRead, nullptr, offsetSeek);
        if (info->offsetIoContext == nullptr) {
            av_freep(&ioBuf);
            return;
        }

        info->fmt_ctx = avformat_alloc_context();
        if (info->fmt_ctx == nullptr) return;
        info->fmt_ctx->pb = info->offsetIoContext;

        if ((ret = avformat_open_input(&info->fmt_ctx, nullptr, nullptr, nullptr)) < 0) {
            LOGE("can't open source file at offset %s (offset=%lld), %s", info->src, fileOffset, av_err2str(ret));
            return;
        }
    } else {
        if ((ret = avformat_open_input(&info->fmt_ctx, info->src, nullptr, nullptr)) < 0) {
            LOGE("can't open source file %s, %s", info->src, av_err2str(ret));
            return;
        }
    }
    if (env->ExceptionCheck()) return;

    if ((ret = avformat_find_stream_info(info->fmt_ctx, NULL)) < 0) {
        LOGE("can't find stream information %s, %s", info->src, av_err2str(ret));
        return;
    }
    if (env->ExceptionCheck()) return;

    if ((ret = av_find_best_stream(info->fmt_ctx, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0)) >= 0) {
        info->video_stream = info->fmt_ctx->streams[ret];
    }

    if ((ret = av_find_best_stream(info->fmt_ctx, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0)) >= 0) {
        info->audio_stream = info->fmt_ctx->streams[ret];
    }

    if (info->video_stream == nullptr) {
        LOGE("can't find video stream in the input, aborting %s", info->src);
        return;
    }

    jint *dataArr = env->GetIntArrayElements(data, 0);
    if (env->ExceptionCheck()) {
        if (dataArr != nullptr) env->ReleaseIntArrayElements(data, dataArr, JNI_ABORT);
        return;
    }
    if (dataArr != nullptr) {
        //https://developer.android.com/guide/topics/media/media-formats
        dataArr[PARAM_NUM_SUPPORTED_VIDEO_CODEC] =
                info->video_stream->codecpar->codec_id == AV_CODEC_ID_H264 ||
                info->video_stream->codecpar->codec_id == AV_CODEC_ID_H263 ||
                info->video_stream->codecpar->codec_id == AV_CODEC_ID_MPEG4 ||
                info->video_stream->codecpar->codec_id == AV_CODEC_ID_VP8 ||
                info->video_stream->codecpar->codec_id == AV_CODEC_ID_VP9 ||
                info->video_stream->codecpar->codec_id == AV_CODEC_ID_HEVC;

        /*
        if (strstr(info->fmt_ctx->iformat->name, "mov") != 0 && dataArr[PARAM_NUM_SUPPORTED_VIDEO_CODEC]) {
            MOVStreamContext *mov = (MOVStreamContext *) info->video_stream->priv_data;
            dataArr[PARAM_NUM_VIDEO_FRAME_SIZE] = (jint) mov->data_size;

            if (info->audio_stream != nullptr) {
                mov = (MOVStreamContext *) info->audio_stream->priv_data;
                dataArr[PARAM_NUM_AUDIO_FRAME_SIZE] = (jint) mov->data_size;
            }
        }
         */

        if (info->audio_stream != nullptr) {
            //https://developer.android.com/guide/topics/media/media-formats
            dataArr[PARAM_NUM_SUPPORTED_AUDIO_CODEC] =
                    info->audio_stream->codecpar->codec_id == AV_CODEC_ID_AAC ||
                    info->audio_stream->codecpar->codec_id == AV_CODEC_ID_AAC_LATM ||
                    info->audio_stream->codecpar->codec_id == AV_CODEC_ID_VORBIS ||
                    info->audio_stream->codecpar->codec_id == AV_CODEC_ID_AMR_NB ||
                    info->audio_stream->codecpar->codec_id == AV_CODEC_ID_AMR_WB ||
                    info->audio_stream->codecpar->codec_id == AV_CODEC_ID_FLAC ||
                    info->audio_stream->codecpar->codec_id == AV_CODEC_ID_MP3 ||
                    // not supported codec, skip audio in this case
                    info->audio_stream->codecpar->codec_id == AV_CODEC_ID_ADPCM_IMA_WAV ||
                    info->audio_stream->codecpar->codec_id == AV_CODEC_ID_OPUS;
            dataArr[PARAM_NUM_HAS_AUDIO] = 1;
        } else {
            dataArr[PARAM_NUM_HAS_AUDIO] = 0;
        }

        dataArr[PARAM_NUM_BITRATE] = (jint) info->video_stream->codecpar->bit_rate;
        dataArr[PARAM_NUM_WIDTH] = info->video_stream->codecpar->width;
        dataArr[PARAM_NUM_HEIGHT] = info->video_stream->codecpar->height;
        dataArr[PARAM_NUM_ROTATION] = (jint) getVideoRotation(info->video_stream);
        if (info->video_stream->codecpar->codec_id == AV_CODEC_ID_H264 || info->video_stream->codecpar->codec_id == AV_CODEC_ID_HEVC) {
            dataArr[PARAM_NUM_FRAMERATE] = (jint) av_q2d(info->video_stream->avg_frame_rate);
        } else {
            dataArr[PARAM_NUM_FRAMERATE] = (jint) av_q2d(info->video_stream->r_frame_rate);
        }
        dataArr[PARAM_NUM_DURATION] = (int32_t) (info->fmt_ctx->duration * 1000 / AV_TIME_BASE);
        env->ReleaseIntArrayElements(data, dataArr, 0);
    }
    } catch (const std::bad_alloc &) {
        return;
    }
}

static bool isStreamCanceled(VideoInfo *info) {
    if (info->stream == nullptr) {
        return false;
    }
    DecoderJniEnv access;
    JNIEnv *jniEnv = access.env;
    if (jniEnv == nullptr) {
        info->protocolFailed = true;
        return true;
    }
    jboolean canceled = false;
    if (!jniEnv->ExceptionCheck()) {
        canceled = jniEnv->CallBooleanMethod(info->stream, jclass_AnimatedFileDrawableStream_isCanceled);
    }
    if (jniEnv->ExceptionCheck()) {
        info->protocolFailed = true;
        canceled = true;
    }
    return canceled;
}

static bool initVideoReader(VideoInfo *info) {
    if (open_codec_context(&info->video_stream_idx, &info->video_dec_ctx, info->fmt_ctx, AVMEDIA_TYPE_VIDEO) < 0) return false;
    if (info->timed && info->protocolFailed) return false;
    info->video_stream = info->fmt_ctx->streams[info->video_stream_idx];
    info->reader = new (std::nothrow) VideoFrameReader(info->fmt_ctx, info->video_dec_ctx, info->video_stream_idx);
    if (info->reader == nullptr || !info->reader->valid()) return false;
    try {
        info->reader->shouldAbort = [info]() {
            return info->stopped || info->seeking || (info->timed ? timedCancelled(info) : isStreamCanceled(info));
        };
    } catch (const std::bad_alloc &) { return false; }
    return true;
}

static VideoInfo *failedDecoderOpen(JNIEnv *env, VideoInfo *info) {
    info->protocolFailed = true;
    if (info->stream != nullptr) {
        env->DeleteGlobalRef(info->stream);
        info->stream = nullptr;
    }
    delete info;
    return nullptr;
}

static VideoInfo *openDecoderMode(JNIEnv *env, jstring src, jintArray data, jint account, jlong streamFileSize, jobject stream, jboolean preview, bool timed) {
    if (env->ExceptionCheck() || src == nullptr) return nullptr;
    VideoInfo *info = new (std::nothrow) VideoInfo();
    if (info == nullptr) return nullptr;
    info->timed = timed;

    char const *srcString = env->GetStringUTFChars(src, 0);
    if (env->ExceptionCheck() || srcString == nullptr) {
        if (srcString != nullptr) env->ReleaseStringUTFChars(src, srcString);
        return failedDecoderOpen(env, info);
    }
    size_t len = strlen(srcString);
    info->src = len > 0 ? new (std::nothrow) char[len + 1] : nullptr;
    if (info->src == nullptr) {
        env->ReleaseStringUTFChars(src, srcString);
        return failedDecoderOpen(env, info);
    }
    memcpy(info->src, srcString, len);
    info->src[len] = '\0';
    if (srcString != 0) {
        env->ReleaseStringUTFChars(src, srcString);
    }
    if (env->ExceptionCheck()) return failedDecoderOpen(env, info);

    try {
    int ret;
    if (streamFileSize != 0) {
        info->file_size = streamFileSize;
        DEBUG_REF("gifvideo.cpp new stream");
        info->stream = env->NewGlobalRef(stream);
        if (env->ExceptionCheck() || info->stream == nullptr) return failedDecoderOpen(env, info);
        info->account = account;
        info->fd = open(info->src, O_RDONLY, S_IRUSR);

        info->ioBuffer = (unsigned char *) av_malloc(64 * 1024);
        if (info->ioBuffer == nullptr) return failedDecoderOpen(env, info);
        info->ioContext = avio_alloc_context(info->ioBuffer, 64 * 1024, 0, info, readCallback, nullptr, seekCallback);
        if (info->ioContext == nullptr) {
            // avio didn't take ownership of the buffer; free it here. After a
            // successful alloc the buffer lives in ioContext->buffer and is
            // freed by the destructor (and may be reallocated by FFmpeg).
            av_freep(&info->ioBuffer);
            return failedDecoderOpen(env, info);
        }

        info->fmt_ctx = avformat_alloc_context();
        if (info->fmt_ctx == nullptr) return failedDecoderOpen(env, info);
        info->fmt_ctx->pb = info->ioContext;
        if (timed) info->fmt_ctx->interrupt_callback = {timedInterrupt, info};

        AVDictionary *options = NULL;
        ret = av_dict_set(&options, "usetoc", "1", 0);
        if (ret < 0) { av_dict_free(&options); return failedDecoderOpen(env, info); }
        ret = avformat_open_input(&info->fmt_ctx, "http://localhost/file", NULL, &options);
        av_dict_free(&options);
        if (env->ExceptionCheck() || info->protocolFailed) return failedDecoderOpen(env, info);
        if (ret < 0) {
            LOGE("can't open source file %s, %s", info->src, av_err2str(ret));
            return failedDecoderOpen(env, info);
        }
        info->fmt_ctx->flags |= AVFMT_FLAG_FAST_SEEK;
        if (preview) {
            info->fmt_ctx->flags |= AVFMT_FLAG_NOBUFFER;
        }
    } else {
        if (timed) {
            info->fmt_ctx = avformat_alloc_context();
            if (info->fmt_ctx == nullptr) return failedDecoderOpen(env, info);
            info->fmt_ctx->interrupt_callback = {timedInterrupt, info};
        }
        if ((ret = avformat_open_input(&info->fmt_ctx, info->src, NULL, NULL)) < 0) {
            LOGE("can't open source file %s, %s", info->src, av_err2str(ret));
            return failedDecoderOpen(env, info);
        }
        if (env->ExceptionCheck() || info->protocolFailed) return failedDecoderOpen(env, info);
    }

    ret = avformat_find_stream_info(info->fmt_ctx, NULL);
    if (env->ExceptionCheck() || info->protocolFailed) return failedDecoderOpen(env, info);
    if (ret < 0) {
        LOGE("can't find stream information %s, %s", info->src, av_err2str(ret));
        return failedDecoderOpen(env, info);
    }

    if (!initVideoReader(info)) {
        LOGE("can't find video stream in the input, aborting %s", info->src);
        return failedDecoderOpen(env, info);
    }
    if (env->ExceptionCheck() || info->protocolFailed) return failedDecoderOpen(env, info);
    if (timed) return info;

    jint *dataArr = env->GetIntArrayElements(data, 0);
    if (dataArr != nullptr) {
        dataArr[0] = info->video_dec_ctx->width;
        dataArr[1] = info->video_dec_ctx->height;
        //float pixelWidthHeightRatio = info->video_dec_ctx->sample_aspect_ratio.num / info->video_dec_ctx->sample_aspect_ratio.den; TODO support
        dataArr[2] = (jint) getVideoRotation(info->video_stream);
        dataArr[4] = (int32_t) (info->fmt_ctx->duration * 1000 / AV_TIME_BASE);
        int video_stream_index = -1;
        double fps = 30.0;
        for (int i = 0; i < info->fmt_ctx->nb_streams; i++) {
            if (info->fmt_ctx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
                video_stream_index = i;
                break;
            }
        }
        if (video_stream_index != -1) {
            AVStream* video_stream = info->fmt_ctx->streams[video_stream_index];
            if (video_stream->avg_frame_rate.den && video_stream->avg_frame_rate.num) {
                fps = av_q2d(video_stream->avg_frame_rate);
            } else if(video_stream->r_frame_rate.den && video_stream->r_frame_rate.num) {
                fps = av_q2d(video_stream->r_frame_rate);
            } else {
                /*
                int ticks = video_stream->codec->ticks_per_frame;
                fps = 1.0 / (ticks * av_q2d(video_stream->time_base));
                 */
            }
        }
        dataArr[5] = (int32_t) fps;
        //(int32_t) (1000 * info->video_stream->duration * av_q2d(info->video_stream->time_base));
        env->ReleaseIntArrayElements(data, dataArr, 0);
    }

    //LOGD("successfully opened file %s", info->src);

    if (env->ExceptionCheck()) return failedDecoderOpen(env, info);
    return info;
    } catch (const std::bad_alloc &) {
        return failedDecoderOpen(env, info);
    }
}

extern "C" JNIEXPORT jlong JNICALL Java_org_telegram_ui_Components_AnimatedFileNative_nCreateDecoder(JNIEnv *env, jclass clazz, jstring src, jintArray data, jint account, jlong streamFileSize, jobject stream, jboolean preview) {
    return reinterpret_cast<jlong>(openDecoderMode(env, src, data, account, streamFileSize, stream, preview, false));
}

extern "C" JNIEXPORT void JNICALL Java_org_telegram_ui_Components_AnimatedFileNative_nDestroyDecoder(JNIEnv *env, jclass clazz, jlong ptr) {
    if (ptr == NULL) {
        return;
    }
    VideoInfo *info = (VideoInfo *) (intptr_t) ptr;
    if (info->stream != nullptr) {
        JNIEnv *jniEnv = nullptr;
        JavaVMAttachArgs jvmArgs;
        jvmArgs.version = JNI_VERSION_1_6;

        bool attached;
        if (JNI_EDETACHED == javaVm->GetEnv((void **) &jniEnv, JNI_VERSION_1_6)) {
            javaVm->AttachCurrentThread(&jniEnv, &jvmArgs);
            attached = true;
        } else {
            attached = false;
        }
        jniEnv->CallVoidMethod(info->stream, jclass_AnimatedFileDrawableStream_cancel);
        if (attached) {
            javaVm->DetachCurrentThread();
        }
    }
    delete info;
}

extern "C" JNIEXPORT void JNICALL Java_org_telegram_ui_Components_AnimatedFileNative_nStopDecoder(JNIEnv *env, jclass clazz, jlong ptr) {
    if (ptr == NULL) {
        return;
    }
    VideoInfo *info = (VideoInfo *) (intptr_t) ptr;
    info->stopped = true;
}

extern "C" JNIEXPORT void JNICALL Java_org_telegram_ui_Components_AnimatedFileNative_nPrepareToSeek(JNIEnv *env, jclass clazz, jlong ptr) {
    if (ptr == NULL) {
        return;
    }
    VideoInfo *info = (VideoInfo *) (intptr_t) ptr;
    info->seeking = true;
}

void push_time(JNIEnv *env, VideoInfo* info, AVFrame *frame, jintArray data) {
    jint *dataArr = env->GetIntArrayElements(data, 0);
    dataArr[3] = (jint) (1000 * frame->best_effort_timestamp * av_q2d(info->video_stream->time_base));
    env->ReleaseIntArrayElements(data, dataArr, 0);
}

void push_single_frame(JNIEnv *env, jintArray data) {
    jint *dataArr = env->GetIntArrayElements(data, 0);
    dataArr[7] = 1;
    env->ReleaseIntArrayElements(data, dataArr, 0);
}

extern "C" JNIEXPORT void JNICALL Java_org_telegram_ui_Components_AnimatedFileNative_nSeekToMs(JNIEnv *env, jclass clazz, jlong ptr, jlong ms, jintArray data, jboolean precise) {
    if (ptr == 0) {
        return;
    }
    VideoInfo *info = (VideoInfo *) (intptr_t) ptr;
    info->seeking = false;   // clear before decoding so shouldAbort() won't bail
    info->afterEof = false;

    AVRational tb = info->video_stream->time_base;
    int64_t pts = (int64_t) (ms / av_q2d(tb) / 1000);

    if (!info->reader->seek(pts)) {
        LOGE("can't seek file %s", info->src);
        return;
    }

    if (!precise) {
        // Non-precise: land on the keyframe, don't decode toward the target.
        return;
    }

    double targetSec = ms / 1000.0;
    for (;;) {
        VideoFrameReader::Status st = info->reader->getNextFrame();
        if (st != VideoFrameReader::Status::Ok) {
            // Eof: target lies past the last frame -> rewind to start, as before.
            // Aborted/Error: stop without advancing.
            if (st == VideoFrameReader::Status::Eof) {
                info->reader->seek(0);
            }
            return;
        }

        if (info->reader->frameTimeSeconds() >= targetSec) {
            push_time(env, info, info->reader->frame(), data);
            return;
        }
    }
}

static inline bool writeFrameToBitmap(JNIEnv *env, VideoInfo *info, AVFrame *frame, jintArray data, jobject bitmap, bool timed = false) {
    if (env->IsSameObject(bitmap, NULL)) {
        if (timed) return false;
        push_time(env, info, frame, data);
        return true;
    }
    if (!timed && (data == nullptr || env->GetArrayLength(data) < 4)) {
        return false;
    }
    jint *dataArr = timed ? nullptr : env->GetIntArrayElements(data, 0);
    if (!timed && dataArr == nullptr) {
        return false;
    }
    const int32_t wantedWidth = timed ? frame->width : dataArr[0];
    const int32_t wantedHeight = timed ? frame->height : dataArr[1];
    if (!timed) env->ReleaseIntArrayElements(data, dataArr, JNI_ABORT);

    AndroidBitmapInfo bitmapInfo{};
    if (AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
            bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
            bitmapInfo.width == 0 || bitmapInfo.height == 0) {
        return false;
    }
    int32_t bitmapWidth = bitmapInfo.width;
    int32_t bitmapHeight = bitmapInfo.height;
    int32_t bitmapStride = bitmapInfo.stride;
    if (timed && (bitmapInfo.width > (INT32_MAX - 16) / 4 || bitmapInfo.height > INT32_MAX ||
                  bitmapInfo.stride > INT32_MAX || bitmapInfo.stride < bitmapInfo.width * 4 ||
                  static_cast<uint64_t>(bitmapInfo.stride) * bitmapInfo.height > SIZE_MAX)) return false;

    if (!(wantedWidth == frame->width && wantedHeight == frame->height || wantedWidth == frame->height && wantedHeight == frame->width)) {
        return false;
    }

    void *pixels;
    if (__builtin_expect(AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS, 0)) {
        return false;
    }
    if (pixels == nullptr) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return false;
    }
    bool written = false;

    SwsContext* sws_ctx = nullptr;
    if (frame->format > AV_PIX_FMT_NONE && frame->format < AV_PIX_FMT_NB && frame->format != AV_PIX_FMT_YUVA420P) {
        sws_ctx = info->sws_ctx_holder.get(
            frame->width,
            frame->height,
            (AVPixelFormat) frame->format,
            bitmapWidth,
            bitmapHeight,
            AV_PIX_FMT_RGBA);
    } else if (info->video_dec_ctx->pix_fmt > AV_PIX_FMT_NONE && info->video_dec_ctx->pix_fmt < AV_PIX_FMT_NB && frame->format != AV_PIX_FMT_YUVA420P) {
        sws_ctx = info->sws_ctx_holder.get(
            info->video_dec_ctx->width,
            info->video_dec_ctx->height,
            info->video_dec_ctx->pix_fmt,
            bitmapWidth,
            bitmapHeight,
            AV_PIX_FMT_RGBA);
    }

    if (sws_ctx != nullptr && ((intptr_t) pixels) % 16 == 0) {
        uint8_t __attribute__ ((aligned (16))) *dst_data[1];
        dst_data[0] = (uint8_t *) pixels;

        int32_t dst_stride[1];
        dst_stride[0] = bitmapStride;
        written = sws_scale(sws_ctx,
            frame->data,
            frame->linesize,
            0,
            frame->height,
            dst_data,
            dst_stride
        ) == bitmapHeight;
    } else if (frame->width == bitmapWidth && frame->height == bitmapHeight) {
        if (frame->format == AV_PIX_FMT_YUVA420P) {
            written = libyuv::I420AlphaToARGBMatrix(
                frame->data[0], frame->linesize[0],
                frame->data[2], frame->linesize[2],
                frame->data[1], frame->linesize[1],
                frame->data[3], frame->linesize[3],
                (uint8_t *) pixels,
                bitmapStride,
                &libyuv::kYvuI601Constants,
                bitmapWidth,
                bitmapHeight,
                1
            ) == 0;
        } else if (frame->format == AV_PIX_FMT_YUV444P) {
            written = libyuv::H444ToARGB(
                frame->data[0], frame->linesize[0],
                frame->data[2], frame->linesize[2],
                frame->data[1], frame->linesize[1],
                (uint8_t *) pixels,
                bitmapStride,
                bitmapWidth,
                bitmapHeight
            ) == 0;
        } else if (frame->format == AV_PIX_FMT_YUV420P || frame->format == AV_PIX_FMT_YUVJ420P) {
            if (frame->colorspace == AVColorSpace::AVCOL_SPC_BT709) {
                written = libyuv::H420ToARGB(
                    frame->data[0], frame->linesize[0],
                    frame->data[2], frame->linesize[2],
                    frame->data[1], frame->linesize[1],
                    (uint8_t *) pixels,
                    bitmapStride,
                    bitmapWidth,
                    bitmapHeight
                ) == 0;
            } else {
                written = libyuv::I420ToARGB(
                    frame->data[0], frame->linesize[0],
                    frame->data[2], frame->linesize[2],
                    frame->data[1], frame->linesize[1],
                    (uint8_t *) pixels,
                    bitmapStride,
                    bitmapWidth,
                    bitmapHeight
                ) == 0;
            }
        } else if (frame->format == AV_PIX_FMT_BGRA) {
            written = libyuv::ABGRToARGB(
                frame->data[0], frame->linesize[0],
                (uint8_t *) pixels,
                bitmapStride,
                bitmapWidth,
                bitmapHeight
            ) == 0;
        }
    } else if (sws_ctx != nullptr && ((intptr_t) pixels) % 16 != 0) {
        // fallback if pixels not aligned
        int alignedStride = FFALIGN(bitmapWidth * 4, 16);
        uint64_t alignedBytes = static_cast<uint64_t>(alignedStride) * bitmapHeight;
        size_t bufSize = static_cast<size_t>(alignedBytes);
        uint8_t *alignedBuf = alignedBytes <= SIZE_MAX ? (uint8_t *) av_malloc(bufSize) : nullptr;
        if (timed && alignedBuf == nullptr) info->protocolFailed = true;
        if (alignedBuf != nullptr) {
            uint8_t *dst_data[1] = { alignedBuf };
            int32_t dst_stride[1] = { alignedStride };
            written = sws_scale(sws_ctx,
                      frame->data,
                      frame->linesize,
                      0,
                      frame->height,
                      dst_data,
                      dst_stride
            ) == bitmapHeight;
            if (written && alignedStride == bitmapStride) {
                memcpy(pixels, alignedBuf, bufSize);
            } else if (written) {
                uint8_t *src = alignedBuf;
                uint8_t *dst = (uint8_t *) pixels;
                int copyStride = bitmapWidth * 4;
                for (int i = 0; i < bitmapHeight; i++) {
                    memcpy(dst, src, copyStride);
                    src += alignedStride;
                    dst += bitmapStride;
                }
            }
            av_free(alignedBuf);
        }
    }

    const int unlockResult = AndroidBitmap_unlockPixels(env, bitmap);
    if (!written || unlockResult != ANDROID_BITMAP_RESULT_SUCCESS) {
        return false;
    }
    if (timed) return !env->ExceptionCheck();
    // Timestamp/opacity describe a completed bitmap, never an attempted write.
    dataArr = env->GetIntArrayElements(data, 0);
    if (dataArr == nullptr) {
        return false;
    }
    dataArr[3] = (jint) (1000 * frame->best_effort_timestamp * av_q2d(info->video_stream->time_base));
    if (env->GetArrayLength(data) > 6) {
        dataArr[6] = (frame->format == AV_PIX_FMT_YUV420P ||
                      frame->format == AV_PIX_FMT_YUVJ420P ||
                      frame->format == AV_PIX_FMT_YUV444P) ? 1 : 0;
    }
    env->ReleaseIntArrayElements(data, dataArr, 0);
    return true;
}

extern "C" JNIEXPORT int JNICALL Java_org_telegram_ui_Components_AnimatedFileNative_nGetFrameAtTime(JNIEnv *env, jclass clazz, jlong ptr, jlong ms, jobject bitmap, jintArray data) {
    if (ptr == 0 || bitmap == nullptr || data == nullptr) {
        return 0;
    }
    VideoInfo *info = (VideoInfo *) (intptr_t) ptr;
    info->seeking = false;   // clear before decoding so shouldAbort() won't bail
    info->afterEof = false;

    AVRational tb = info->video_stream->time_base;
    int64_t pts = (int64_t) (ms / av_q2d(tb) / 1000);

    if (!info->reader->seek(pts)) {
        LOGE("can't seek file %s", info->src);
        return 0;
    }

    double targetSec = ms / 1000.0;
    AVFrame *held = av_frame_alloc();   // most recent frame before target (fallback)
    bool haveHeld = false;
    int result = 0;

    for (;;) {
        VideoFrameReader::Status st = info->reader->getNextFrame();
        if (st != VideoFrameReader::Status::Ok) {
            // Eof: target lies at/after the last frame -> emit the last frame we
            // held. Aborted/Error: give up with no frame.
            if (st == VideoFrameReader::Status::Eof && haveHeld) {
                result = writeFrameToBitmap(env, info, held, data, bitmap) ? 1 : 0;
            }
            break;
        }

        AVFrame *frame = info->reader->frame();
        if (info->reader->frameTimeSeconds() >= targetSec) {
            result = writeFrameToBitmap(env, info, frame, data, bitmap) ? 1 : 0;
            break;
        }

        // Frame is before the target: keep it as the fallback and continue.
        av_frame_unref(held);
        av_frame_ref(held, frame);
        haveHeld = true;
    }

    av_frame_free(&held);
    return result;
}

extern "C" JNIEXPORT jint JNICALL Java_org_telegram_ui_Components_AnimatedFileNative_nGetVideoFrame(JNIEnv *env, jclass clazz, jlong ptr, jobject bitmap, jintArray data, jboolean preview, jfloat start_time, jfloat end_time, jboolean loop) {
    if (ptr == 0) {
        return 0;
    }
    VideoInfo *info = (VideoInfo *) (intptr_t) ptr;
    if (info->stopped || info->seeking) {
        return 0;
    }

    AVRational tb = info->video_stream->time_base;
    int64_t startPts = start_time > 0 ? (int64_t) (start_time / av_q2d(tb)) : 0;

    VideoFrameReader::Status st = info->reader->getNextFrame();

    // End of stream, or the frame ran past the trim point -> loop back or finish.
    // Note: end_time is compared against the frame's display-order timestamp,
    // not a packet pts, so B-frame reordering can no longer drop the wrong frame.
    bool pastEnd = st == VideoFrameReader::Status::Ok &&
                   end_time > 0 && info->reader->frameTimeSeconds() > end_time;

    if (st == VideoFrameReader::Status::Eof) {
        if (info->afterEof && !info->isSingleFrame) {
            push_single_frame(env, data);
            info->isSingleFrame = true;
        }
        info->afterEof = true;
    } else {
        info->afterEof = false;
    }

    if (st == VideoFrameReader::Status::Eof || pastEnd) {
        if (!loop) {
            return 2;
        }
        if (!info->reader->seek(startPts)) {
            return 0;
        }
        st = info->reader->getNextFrame();
        // Backward seek resolves to a keyframe before the trim boundary. Do
        // not publish excluded pre-trim frames at the beginning of each loop.
        while (st == VideoFrameReader::Status::Ok && start_time > 0 &&
               info->reader->frameTimeSeconds() < start_time) {
            st = info->reader->getNextFrame();
        }
    }

    if (st != VideoFrameReader::Status::Ok) {
        // Aborted (stopped / seeking / canceled), Error, or Eof after looping.
        return 0;
    }

    if (end_time > 0 && info->reader->frameTimeSeconds() > end_time) {
        return 0;
    }

    AVFrame *frame = info->reader->frame();
    if (bitmap != nullptr) {
        return writeFrameToBitmap(env, info, frame, data, bitmap) ? 1 : 0;
    }
    push_time(env, info, frame, data);
    return 1;
}

static bool timedSource(JNIEnv *env, VideoInfo *info, jlongArray source) {
    if (env->ExceptionCheck() || source == nullptr || info->video_stream == nullptr) return false;
    jsize sourceLength = env->GetArrayLength(source);
    if (env->ExceptionCheck() || sourceLength != 14) return false;
    AVStream *stream = info->video_stream;
    AVRational tb = stream->time_base;
    if (tb.num <= 0 || tb.den <= 0) return false;
    AVRational nominal = stream->avg_frame_rate;
    jlong flags = 1;
    if (nominal.num > 0 && nominal.den > 0) flags |= 2;
    else {
        nominal = stream->r_frame_rate;
        if (nominal.num > 0 && nominal.den > 0) flags |= 4;
        else nominal = {0, 0};
    }
    if (stream->start_time != AV_NOPTS_VALUE) flags |= 8;
    if (stream->duration != AV_NOPTS_VALUE && stream->duration > 0) flags |= 16;
    if (info->fmt_ctx->start_time != AV_NOPTS_VALUE) flags |= 32;
    if (info->fmt_ctx->duration != AV_NOPTS_VALUE && info->fmt_ctx->duration > 0) flags |= 64;
    jlong values[14] = {1, info->video_stream_idx, info->video_dec_ctx->width, info->video_dec_ctx->height,
        static_cast<jlong>(getVideoRotation(stream)), tb.num, tb.den,
        nominal.num > 0 ? nominal.num : AV_NOPTS_VALUE, nominal.den > 0 ? nominal.den : AV_NOPTS_VALUE,
        flags, stream->start_time, (flags & 16) ? stream->duration : AV_NOPTS_VALUE,
        info->fmt_ctx->start_time, (flags & 64) ? info->fmt_ctx->duration : AV_NOPTS_VALUE};
    env->SetLongArrayRegion(source, 0, 14, values);
    if (env->ExceptionCheck()) return false;
    info->timed = true;
    info->fmt_ctx->interrupt_callback = {timedInterrupt, info};
    return true;
}

extern "C" JNIEXPORT jlong JNICALL Java_org_telegram_ui_Components_AnimatedFileNative_nCreateTimedPath(
        JNIEnv *env, jclass clazz, jstring path, jint account, jlong length, jobject stream, jboolean preview, jlongArray source) {
    if (env->ExceptionCheck() || path == nullptr || source == nullptr || length < 0 ||
            (stream == nullptr ? length != 0 : length <= 0 || length > INT32_MAX)) return 0;
    jsize sourceLength = env->GetArrayLength(source);
    if (env->ExceptionCheck() || sourceLength != 14) return 0;
    VideoInfo *info = openDecoderMode(env, path, nullptr, account, length, stream, preview, true);
    if (info == nullptr) return 0;
    if (!timedSource(env, info, source)) { failedDecoderOpen(env, info); return 0; }
    return reinterpret_cast<jlong>(info);
}

extern "C" JNIEXPORT jlong JNICALL Java_org_telegram_ui_Components_AnimatedFileNative_nCreateTimedSnapshot(
        JNIEnv *env, jclass, jint fd, jlong length, jlongArray source) {
    if (fd < 0 || length <= 0 || source == nullptr || env->GetArrayLength(source) != 14) return 0;
    auto *info = new (std::nothrow) VideoInfo();
    if (info == nullptr) return 0;
    info->timed = true;
    info->snapshot = true;
    info->file_size = length;
    info->fd = dup(fd);
    if (info->fd < 0) { delete info; return 0; }
    info->ioBuffer = static_cast<unsigned char *>(av_malloc(64 * 1024));
    if (info->ioBuffer == nullptr) { delete info; return 0; }
    info->ioContext = avio_alloc_context(info->ioBuffer, 64 * 1024, 0, info, readCallback, nullptr, seekCallback);
    if (info->ioContext == nullptr) {
        av_freep(&info->ioBuffer);
        delete info;
        return 0;
    }
    info->fmt_ctx = avformat_alloc_context();
    if (info->fmt_ctx == nullptr) { delete info; return 0; }
    info->fmt_ctx->pb = info->ioContext;
    info->fmt_ctx->interrupt_callback = {timedInterrupt, info};
    if (avformat_open_input(&info->fmt_ctx, nullptr, nullptr, nullptr) < 0 ||
            avformat_find_stream_info(info->fmt_ctx, nullptr) < 0 || !initVideoReader(info) ||
            !timedSource(env, info, source)) { delete info; return 0; }
    return reinterpret_cast<jlong>(info);
}

static bool timedCurrent(VideoInfo *info, jlong intent) {
    return info != nullptr && info->timed && intent > 0 && info->cancelIntent.load() == intent;
}

static int timedState(VideoInfo *info, jlong intent, bool adopt = false) {
    if (info->protocolFailed) return TIMED_PROTOCOL_ERROR;
    if (!timedCurrent(info, intent) || (!adopt && info->activeIntent != intent)) return TIMED_CANCELLED;
    bool cancelled = isStreamCanceled(info);
    return info->protocolFailed ? TIMED_PROTOCOL_ERROR : (cancelled ? TIMED_CANCELLED : 0);
}

extern "C" JNIEXPORT jlong JNICALL Java_org_telegram_ui_Components_AnimatedFileNative_nCancelTimed(JNIEnv *, jclass, jlong ptr) {
    auto *info = reinterpret_cast<VideoInfo *>(ptr);
    if (info == nullptr || !info->timed) return 0;
    int64_t old = info->cancelIntent.load();
    for (;;) {
        int64_t next = old == INT64_MAX || old == 0 ? 0 : old + 1;
        if (info->cancelIntent.compare_exchange_weak(old, next)) return next;
    }
}

extern "C" JNIEXPORT jint JNICALL Java_org_telegram_ui_Components_AnimatedFileNative_nAcquireTimed(
        JNIEnv *env, jclass, jlong ptr, jlong intent, jlongArray descriptor, jint steps, jlong budgetUs) {
    auto *info = reinterpret_cast<VideoInfo *>(ptr);
    if (info == nullptr || !info->timed || descriptor == nullptr || env->GetArrayLength(descriptor) != 4 || steps <= 0 || budgetUs <= 0) return TIMED_PROTOCOL_ERROR;
    int state = timedState(info, intent);
    if (state != 0) return state;
    if (info->readFailed) return TIMED_READ_ERROR;
    if (info->pendingId == 0) {
        if (info->nextFrameId == INT64_MAX) return TIMED_PROTOCOL_ERROR;
        VideoFrameReader::Budget budget{steps, budgetUs};
        VideoFrameReader::Status status = info->reader->getNextFrame(&budget);
        if (status == VideoFrameReader::Status::Ok) {
            info->pendingId = ++info->nextFrameId;
            info->materialized = false;
        }
        state = timedState(info, intent);
        if (state != 0) return state;
        switch (status) {
            case VideoFrameReader::Status::Ok: break;
            case VideoFrameReader::Status::Eof: return TIMED_EOF;
            case VideoFrameReader::Status::Again: return TIMED_AGAIN;
            case VideoFrameReader::Status::Yield: return TIMED_YIELD;
            case VideoFrameReader::Status::Aborted:
            case VideoFrameReader::Status::Error: info->readFailed = true; return TIMED_READ_ERROR;
        }
    }
    AVFrame *frame = info->reader->frame();
    jlong flags = frame->best_effort_timestamp != AV_NOPTS_VALUE ? 1 : 0;
    if (frame->duration > 0) flags |= 2;
    if (frame->format == AV_PIX_FMT_YUV420P || frame->format == AV_PIX_FMT_YUVJ420P || frame->format == AV_PIX_FMT_YUV444P) flags |= 4;
    jlong values[4] = {info->pendingId, frame->best_effort_timestamp, frame->duration > 0 ? frame->duration : AV_NOPTS_VALUE, flags};
    env->SetLongArrayRegion(descriptor, 0, 4, values);
    if (env->ExceptionCheck()) return TIMED_PROTOCOL_ERROR;
    state = timedState(info, intent);
    return state != 0 ? state : TIMED_ACQUIRED;
}

extern "C" JNIEXPORT jint JNICALL Java_org_telegram_ui_Components_AnimatedFileNative_nMaterializeTimed(
        JNIEnv *env, jclass, jlong ptr, jlong intent, jlong id, jobject bitmap) {
    auto *info = reinterpret_cast<VideoInfo *>(ptr);
    if (info == nullptr || !info->timed) return TIMED_PROTOCOL_ERROR;
    int state = timedState(info, intent);
    if (state != 0) return state;
    if (id <= 0 || info->pendingId != id) return TIMED_PROTOCOL_ERROR;
    info->materialized = false;
    bool written = bitmap != nullptr && writeFrameToBitmap(env, info, info->reader->frame(), nullptr, bitmap, true);
    if (!timedCurrent(info, intent)) return TIMED_CANCELLED;
    if (env->ExceptionCheck()) return TIMED_WRITE_ERROR;
    state = timedState(info, intent);
    if (state != 0) return state;
    if (!written) return TIMED_WRITE_ERROR;
    info->materialized = true;
    return TIMED_MATERIALIZED;
}

extern "C" JNIEXPORT jint JNICALL Java_org_telegram_ui_Components_AnimatedFileNative_nResolveTimed(
        JNIEnv *, jclass, jlong ptr, jlong intent, jlong id, jint disposition) {
    auto *info = reinterpret_cast<VideoInfo *>(ptr);
    if (info == nullptr || !info->timed || id <= 0 || (disposition != 1 && disposition != 2)) return TIMED_PROTOCOL_ERROR;
    int state = timedState(info, intent);
    if (state != 0) return state;
    if (id == info->lastResolvedId) return disposition == info->lastDisposition ?
            (disposition == 1 ? TIMED_COMMITTED : TIMED_DISCARDED) : TIMED_PROTOCOL_ERROR;
    if (id != info->pendingId || (disposition == 1 && !info->materialized)) return TIMED_PROTOCOL_ERROR;
    info->lastResolvedId = id;
    info->lastDisposition = disposition;
    info->pendingId = 0;
    info->materialized = false;
    return info->cancelIntent.load() != intent ? TIMED_CANCELLED : (disposition == 1 ? TIMED_COMMITTED : TIMED_DISCARDED);
}

extern "C" JNIEXPORT jint JNICALL Java_org_telegram_ui_Components_AnimatedFileNative_nSeekTimed(
        JNIEnv *, jclass, jlong ptr, jlong intent, jlong rawTicks) {
    auto *info = reinterpret_cast<VideoInfo *>(ptr);
    if (info == nullptr || !info->timed || rawTicks == AV_NOPTS_VALUE) return TIMED_PROTOCOL_ERROR;
    int state = timedState(info, intent, true);
    if (state != 0) return state;
    info->activeIntent = intent;
    info->pendingId = 0;
    info->lastResolvedId = 0;
    info->lastDisposition = 0;
    info->materialized = false;
    if (info->ioContext != nullptr) {
        info->ioContext->error = 0;
        info->ioContext->eof_reached = 0;
    }
    bool ok = info->reader->seek(rawTicks);
    info->readFailed = !ok;
    state = timedState(info, intent);
    if (state != 0) return state;
    return ok ? TIMED_SEEKED : TIMED_READ_ERROR;
}

extern "C" JNIEXPORT void JNICALL Java_org_telegram_ui_Components_AnimatedFileNative_nDestroyTimed(JNIEnv *, jclass, jlong ptr) {
    delete reinterpret_cast<VideoInfo *>(ptr);
}

extern "C" jint videoOnJNILoad(JavaVM *vm, JNIEnv *env) {
    //av_log_set_callback(custom_log);
    DEBUG_REF("gifvideo.cpp AnimatedFileDrawableStream ref");
    jclass_AnimatedFileDrawableStream = (jclass) env->NewGlobalRef(env->FindClass("org/telegram/messenger/AnimatedFileDrawableStream"));
    if (jclass_AnimatedFileDrawableStream == 0) {
        return JNI_FALSE;
    }
    jclass_AnimatedFileDrawableStream_read = env->GetMethodID(jclass_AnimatedFileDrawableStream, "read", "(II)I");
    if (jclass_AnimatedFileDrawableStream_read == 0) {
        return JNI_FALSE;
    }
    jclass_AnimatedFileDrawableStream_cancel = env->GetMethodID(jclass_AnimatedFileDrawableStream, "cancel", "()V");
    if (jclass_AnimatedFileDrawableStream_cancel == 0) {
        return JNI_FALSE;
    }
    jclass_AnimatedFileDrawableStream_isFinishedLoadingFile = env->GetMethodID(jclass_AnimatedFileDrawableStream, "isFinishedLoadingFile", "()Z");
    if (jclass_AnimatedFileDrawableStream_isFinishedLoadingFile == 0) {
        return JNI_FALSE;
    }
    jclass_AnimatedFileDrawableStream_isCanceled = env->GetMethodID(jclass_AnimatedFileDrawableStream, "isCanceled", "()Z");
    if (jclass_AnimatedFileDrawableStream_isCanceled == 0) {
        return JNI_FALSE;
    }
    jclass_AnimatedFileDrawableStream_getFinishedFilePath = env->GetMethodID(jclass_AnimatedFileDrawableStream, "getFinishedFilePath", "()Ljava/lang/String;");
    if (jclass_AnimatedFileDrawableStream_getFinishedFilePath == 0) {
        return JNI_FALSE;
    }

    return JNI_TRUE;
}
