/* Modifications Copyright (C) 2026 Ettacent */

#include <jni.h>
#include <ogg/ogg.h>
#include <stdio.h>
#include <opus.h>
#include <stdlib.h>
#include <time.h>
#include <opusfile.h>
#include <math.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>
#include <rnnoise.h>
#include "c_utils.h"
#include "libavformat/avformat.h"
#include "libavcodec/avcodec.h"

typedef struct {
    int version;
    int channels; /* Number of channels: 1..255 */
    int preskip;
    ogg_uint32_t input_sample_rate;
    int gain; /* in dB S7.8 should be zero whenever possible */
    int channel_mapping;
    /* The rest is only used if channel_mapping != 0 */
    int nb_streams;
    int nb_coupled;
    unsigned char stream_map[255];
} OpusHeader;

typedef struct {
    unsigned char *data;
    int maxlen;
    int pos;
} Packet;

typedef struct {
    const unsigned char *data;
    int maxlen;
    int pos;
} ROPacket;

typedef struct {
    void *readdata;
    opus_int64 total_samples_per_channel;
    int rawmode;
    int channels;
    long rate;
    int gain;
    int samplesize;
    int endianness;
    char *infilename;
    int ignorelength;
    int skip;
    int extraout;
    char *comments;
    int comments_length;
    int copy_comments;
} oe_enc_opt;

static int write_uint32(Packet *p, ogg_uint32_t val) {
    if (p->pos > p->maxlen - 4) {
        return 0;
    }
    p->data[p->pos  ] = (val    ) & 0xFF;
    p->data[p->pos+1] = (val>> 8) & 0xFF;
    p->data[p->pos+2] = (val>>16) & 0xFF;
    p->data[p->pos+3] = (val>>24) & 0xFF;
    p->pos += 4;
    return 1;
}

static int write_uint16(Packet *p, ogg_uint16_t val) {
    if (p->pos > p->maxlen-2) {
        return 0;
    }
    p->data[p->pos  ] = (val    ) & 0xFF;
    p->data[p->pos+1] = (val>> 8) & 0xFF;
    p->pos += 2;
    return 1;
}

static int write_chars(Packet *p, const unsigned char *str, int nb_chars)
{
    int i;
    if (p->pos>p->maxlen-nb_chars)
        return 0;
    for (i=0;i<nb_chars;i++)
        p->data[p->pos++] = str[i];
    return 1;
}

static int read_uint32(ROPacket *p, ogg_uint32_t *val)
{
    if (p->pos>p->maxlen-4)
        return 0;
    *val =  (ogg_uint32_t)p->data[p->pos  ];
    *val |= (ogg_uint32_t)p->data[p->pos+1]<< 8;
    *val |= (ogg_uint32_t)p->data[p->pos+2]<<16;
    *val |= (ogg_uint32_t)p->data[p->pos+3]<<24;
    p->pos += 4;
    return 1;
}

static int read_uint16(ROPacket *p, ogg_uint16_t *val)
{
    if (p->pos>p->maxlen-2)
        return 0;
    *val =  (ogg_uint16_t)p->data[p->pos  ];
    *val |= (ogg_uint16_t)p->data[p->pos+1]<<8;
    p->pos += 2;
    return 1;
}

static int read_chars(ROPacket *p, unsigned char *str, int nb_chars)
{
    int i;
    if (p->pos>p->maxlen-nb_chars)
        return 0;
    for (i=0;i<nb_chars;i++)
        str[i] = p->data[p->pos++];
    return 1;
}

int opus_header_to_packet(const OpusHeader *h, unsigned char *packet, int len) {
    int i;
    Packet p;
    unsigned char ch;

    p.data = packet;
    p.maxlen = len;
    p.pos = 0;
    if (len < 19) {
        return 0;
    }
    if (!write_chars(&p, (const unsigned char *)"OpusHead", 8)) {
        return 0;
    }

    ch = 1;
    if (!write_chars(&p, &ch, 1)) {
        return 0;
    }

    ch = h->channels;
    if (!write_chars(&p, &ch, 1)) {
        return 0;
    }

    if (!write_uint16(&p, h->preskip)) {
        return 0;
    }

    if (!write_uint32(&p, h->input_sample_rate)) {
        return 0;
    }

    if (!write_uint16(&p, h->gain)) {
        return 0;
    }

    ch = h->channel_mapping;
    if (!write_chars(&p, &ch, 1)) {
        return 0;
    }

    if (h->channel_mapping != 0) {
        ch = h->nb_streams;
        if (!write_chars(&p, &ch, 1)) {
            return 0;
        }

        ch = h->nb_coupled;
        if (!write_chars(&p, &ch, 1)) {
            return 0;
        }

        /* Multi-stream support */
        for (i = 0; i < h->channels; i++) {
            if (!write_chars(&p, &h->stream_map[i], 1)) {
                return 0;
            }
        }
    }

    return p.pos;
}

#define writeint(buf, base, val) do { buf[base + 3] = ((val) >> 24) & 0xff; \
buf[base + 2]=((val) >> 16) & 0xff; \
buf[base + 1]=((val) >> 8) & 0xff; \
buf[base] = (val) & 0xff; \
} while(0)

static int comment_init(char **comments, int *length, const char *vendor_string) {
    // The 'vendor' field should be the actual encoding library used
    size_t vendor_length = strlen(vendor_string);
    int user_comment_list_length = 0;
    size_t len = 8 + 4 + vendor_length + 4;
    char *p = (char *)malloc(len);
    if (!p) return 0;
    memcpy(p, "OpusTags", 8);
    writeint(p, 8, vendor_length);
    memcpy(p + 12, vendor_string, vendor_length);
    writeint(p, 12 + vendor_length, user_comment_list_length);
    *length = len;
    *comments = p;
    return 1;
}

static int comment_pad(char **comments, int* length, size_t amount) {
    if (amount > 0) {
        char *p = *comments;
        // Make sure there is at least amount worth of padding free, and round up to the maximum that fits in the current ogg segments
        size_t newlen = (*length + amount + 255) / 255 * 255 - 1;
        char *replacement = realloc(p, newlen);
        if (!replacement) return 0;
        p = replacement;
        for (int32_t i = *length; i < newlen; i++) {
            p[i] = 0;
        }
        *comments = p;
        *length = newlen;
    }
    return 1;
}

static int writeOggPage(ogg_page *page, FILE *os) {
    int written = fwrite(page->header, sizeof(unsigned char), (size_t) page->header_len, os);
    written += fwrite(page->body, sizeof(unsigned char), (size_t) page->body_len, os);
    return written;
}

const opus_int32 bitrate = 30 * 1024;
static const int opus_application = OPUS_APPLICATION_AUDIO;
const opus_int32 frame_size = 960;
const int with_cvbr = 1;
const int max_ogg_delay = 0;
const int comment_padding = 512;

opus_int32 rate = 48000;
opus_int32 coding_rate = 48000;

ogg_int32_t _packetId;
OpusEncoder *_encoder = 0;
uint8_t *_packet = 0;
ogg_stream_state os;
char *_filePath;
FILE *_fileOs = 0;
oe_enc_opt inopt;
OpusHeader header;
opus_int32 min_bytes;
int max_frame_bytes;
ogg_packet op;
ogg_page og;
opus_int64 bytes_written;
opus_int64 pages_out;
opus_int64 total_samples;
ogg_int64_t enc_granulepos;
ogg_int64_t last_granulepos;
int size_segments;
int last_segments;
int serialno;
static pthread_mutex_t recorderMutex = PTHREAD_MUTEX_INITIALIZER;
static int recorderReady;
static int recorderStreamInitialized;

static void cleanupRecorderLocked() {
    recorderReady = 0;

    if (recorderStreamInitialized && _fileOs) {
        while (ogg_stream_flush(&os, &og)) {
            writeOggPage(&og, _fileOs);
        }
    } else if (recorderStreamInitialized) {
        ogg_stream_flush(&os, &og);
    }

    if (_encoder) {
        opus_encoder_destroy(_encoder);
        _encoder = 0;
    }

    if (recorderStreamInitialized) ogg_stream_clear(&os);
    recorderStreamInitialized = 0;
    free(inopt.comments);
    inopt.comments = 0;

    if (_packet) {
        free(_packet);
        _packet = 0;
    }

    if (_fileOs) {
        fclose(_fileOs);
        _fileOs = 0;
    }

    _packetId = -1;
    bytes_written = 0;
    pages_out = 0;
    total_samples = 0;
    enc_granulepos = 0;
    size_segments = 0;
    last_segments = 0;
    last_granulepos = 0;
    if (_filePath) {
        free(_filePath);
        _filePath = 0;
    }
    memset(&os, 0, sizeof(ogg_stream_state));
    memset(&inopt, 0, sizeof(oe_enc_opt));
    memset(&header, 0, sizeof(OpusHeader));
    memset(&op, 0, sizeof(ogg_packet));
    memset(&og, 0, sizeof(ogg_page));
}

static int initRecorderLocked(const char *path, opus_int32 sampleRate) {
    cleanupRecorderLocked();

    coding_rate = sampleRate;
    rate = sampleRate;

    if (!path) {
        LOGE("path is null");
        return 0;
    }

    size_t length = strlen(path);
    _filePath = (char*) malloc(length + 1);
    if (!_filePath) goto fail;
    strcpy(_filePath, path);

    _fileOs = fopen(path, "w");
    if (!_fileOs) {
        LOGE("error cannot open file: %s", path);
        goto fail;
    }

    inopt.rate = rate;
    inopt.gain = 0;
    inopt.endianness = 0;
    inopt.copy_comments = 0;
    inopt.rawmode = 0;
    inopt.ignorelength = 0;
    inopt.samplesize = 16;
    inopt.channels = 1;
    inopt.skip = 0;

    if (!comment_init(&inopt.comments, &inopt.comments_length, opus_get_version_string())) goto fail;

    if (rate != coding_rate) {
        LOGE("Invalid rate");
        goto fail;
    }

    header.channels = 1;
    header.channel_mapping = 0;
    header.input_sample_rate = rate;
    header.gain = inopt.gain;
    header.nb_streams = 1;

    int result = OPUS_OK;
    _encoder = opus_encoder_create(coding_rate, 1, opus_application, &result);
    if (result != OPUS_OK || !_encoder) {
        LOGE("Error cannot create encoder: %s", opus_strerror(result));
        goto fail;
    }

    min_bytes = max_frame_bytes = (1275 * 3 + 7) * header.nb_streams;
    _packet = malloc(max_frame_bytes);
    if (!_packet) goto fail;

    result = opus_encoder_ctl(_encoder, OPUS_SET_BITRATE(bitrate));
    //result = opus_encoder_ctl(_encoder, OPUS_SET_COMPLEXITY(10));
    if (result != OPUS_OK) {
        LOGE("Error OPUS_SET_BITRATE returned: %s", opus_strerror(result));
        goto fail;
    }

#ifdef OPUS_SET_LSB_DEPTH
    result = opus_encoder_ctl(_encoder, OPUS_SET_LSB_DEPTH(MAX(8, MIN(24, inopt.samplesize))));
    if (result != OPUS_OK) {
        LOGE("Warning OPUS_SET_LSB_DEPTH returned: %s", opus_strerror(result));
    }
#endif

    opus_int32 lookahead;
    result = opus_encoder_ctl(_encoder, OPUS_GET_LOOKAHEAD(&lookahead));
    if (result != OPUS_OK) {
        LOGE("Error OPUS_GET_LOOKAHEAD returned: %s", opus_strerror(result));
        goto fail;
    }

    inopt.skip += lookahead;
    header.preskip = (int)(inopt.skip * (48000.0 / coding_rate));
    inopt.extraout = (int)(header.preskip * (rate / 48000.0));

    if (ogg_stream_init(&os, serialno = rand()) == -1) {
        LOGE("Error: stream init failed");
        goto fail;
    }
    recorderStreamInitialized = 1;

    unsigned char header_data[100];
    int packet_size = opus_header_to_packet(&header, header_data, 100);
    op.packet = header_data;
    op.bytes = packet_size;
    op.b_o_s = 1;
    op.e_o_s = 0;
    op.granulepos = 0;
    op.packetno = 0;
    if (ogg_stream_packetin(&os, &op) != 0) goto fail;

    while ((result = ogg_stream_flush(&os, &og))) {
        if (!result) {
            break;
        }

        int pageBytesWritten = writeOggPage(&og, _fileOs);
        if (pageBytesWritten != og.header_len + og.body_len) {
            LOGE("Error: failed writing header to output stream");
            goto fail;
        }
        bytes_written += pageBytesWritten;
        pages_out++;
    }

    if (!comment_pad(&inopt.comments, &inopt.comments_length, comment_padding)) goto fail;
    op.packet = (unsigned char *)inopt.comments;
    op.bytes = inopt.comments_length;
    op.b_o_s = 0;
    op.e_o_s = 0;
    op.granulepos = 0;
    op.packetno = 1;
    if (ogg_stream_packetin(&os, &op) != 0) goto fail;

    while ((result = ogg_stream_flush(&os, &og))) {
        if (result == 0) {
            break;
        }

        int writtenPageBytes = writeOggPage(&og, _fileOs);
        if (writtenPageBytes != og.header_len + og.body_len) {
            LOGE("Error: failed writing header to output stream");
            goto fail;
        }

        bytes_written += writtenPageBytes;
        pages_out++;
    }

    free(inopt.comments);
    inopt.comments = 0;
    recorderReady = 1;

    return 1;
fail:
    cleanupRecorderLocked();
    return 0;
}

static int writeFrameLocked(uint8_t *framePcmBytes, uint32_t frameByteCount, int end) {
    if (!recorderReady || !_encoder || !_packet || !_fileOs || !recorderStreamInitialized
            || coding_rate <= 0 || (frameByteCount & 1) || frameByteCount > frame_size * 2
            || (frameByteCount != 0 && !framePcmBytes)) return 0;
    size_t cur_frame_size = frame_size;
    _packetId++;

    opus_int32 nb_samples = frameByteCount / 2;
    total_samples += nb_samples;
    op.e_o_s = end;

    int nbBytes = 0;

    if (nb_samples != 0) {
        uint8_t *paddedFrameBytes = framePcmBytes;
        int freePaddedFrameBytes = 0;

        if (nb_samples < cur_frame_size) {
            paddedFrameBytes = malloc(cur_frame_size * 2);
            if (!paddedFrameBytes) return 0;
            freePaddedFrameBytes = 1;
            memcpy(paddedFrameBytes, framePcmBytes, frameByteCount);
            memset(paddedFrameBytes + nb_samples * 2, 0, cur_frame_size * 2 - nb_samples * 2);
        }

        nbBytes = opus_encode(_encoder, (opus_int16 *)paddedFrameBytes, cur_frame_size, _packet, max_frame_bytes / 10);
        if (freePaddedFrameBytes) {
            free(paddedFrameBytes);
        }

        if (nbBytes < 0) {
            LOGE("Encoding failed: %s. Aborting.", opus_strerror(nbBytes));
            return 0;
        }

        enc_granulepos += cur_frame_size * 48000 / coding_rate;
        size_segments = (nbBytes + 255) / 255;
        min_bytes = MIN(nbBytes, min_bytes);
    }

    while ((((size_segments <= 255) && (last_segments + size_segments > 255)) || (enc_granulepos - last_granulepos > max_ogg_delay)) && ogg_stream_flush_fill(&os, &og, 255 * 255)) {
        if (ogg_page_packets(&og) != 0) {
            last_granulepos = ogg_page_granulepos(&og);
        }

        last_segments -= og.header[26];
        int writtenPageBytes = writeOggPage(&og, _fileOs);
        if (writtenPageBytes != og.header_len + og.body_len) {
            LOGE("Error: failed writing data to output stream");
            return 0;
        }
        bytes_written += writtenPageBytes;
        pages_out++;
    }

    op.packet = _packet;
    op.bytes = nbBytes;
    op.b_o_s = 0;
    op.granulepos = enc_granulepos;
    if (op.e_o_s) {
        op.granulepos = ((total_samples * 48000 + rate - 1) / rate) + header.preskip;
    }
    op.packetno = 2 + _packetId;
    if (ogg_stream_packetin(&os, &op) != 0) return 0;
    last_segments += size_segments;

    while ((op.e_o_s || (enc_granulepos + (frame_size * 48000 / coding_rate) - last_granulepos > max_ogg_delay) || (last_segments >= 255)) ? ogg_stream_flush_fill(&os, &og, 255 * 255) : ogg_stream_pageout_fill(&os, &og, 255 * 255)) {
        if (ogg_page_packets(&og) != 0) {
            last_granulepos = ogg_page_granulepos(&og);
        }
        last_segments -= og.header[26];
        int writtenPageBytes = writeOggPage(&og, _fileOs);
        if (writtenPageBytes != og.header_len + og.body_len) {
            LOGE("Error: failed writing data to output stream");
            return 0;
        }
        bytes_written += writtenPageBytes;
        pages_out++;
    }

    return 1;
}

void cleanupRecorder() {
    pthread_mutex_lock(&recorderMutex);
    cleanupRecorderLocked();
    pthread_mutex_unlock(&recorderMutex);
}

int initRecorder(const char *path, opus_int32 sampleRate) {
    pthread_mutex_lock(&recorderMutex);
    int result = initRecorderLocked(path, sampleRate);
    pthread_mutex_unlock(&recorderMutex);
    return result;
}

int writeFrame(uint8_t *framePcmBytes, uint32_t frameByteCount, int end) {
    pthread_mutex_lock(&recorderMutex);
    int result = writeFrameLocked(framePcmBytes, frameByteCount, end);
    pthread_mutex_unlock(&recorderMutex);
    return result;
}

JNIEXPORT jint Java_org_telegram_messenger_MediaController_startRecord(JNIEnv *env, jclass class, jstring path, jint sampleRate) {
    if (!path) return 0;
    const char *pathStr = (*env)->GetStringUTFChars(env, path, 0);
    if (!pathStr) return 0;

    int32_t result = initRecorder(pathStr, sampleRate);

    if (pathStr != 0) {
        (*env)->ReleaseStringUTFChars(env, path, pathStr);
    }

    return result;
}

JNIEXPORT jint Java_org_telegram_messenger_MediaController_writeFrame(JNIEnv *env, jclass class, jobject frame, jint len) {
    if (len < 0 || (len & 1) || len > frame_size * 2) return 0;
    jbyte *frameBytes = 0;
    if (len != 0) {
        if (!frame || (*env)->GetDirectBufferCapacity(env, frame) < len) return 0;
        frameBytes = (*env)->GetDirectBufferAddress(env, frame);
        if (!frameBytes) return 0;
    }
    return writeFrame((uint8_t *) frameBytes, (uint32_t) len, len / 2 < frame_size);
}

JNIEXPORT void Java_org_telegram_messenger_MediaController_stopRecord(JNIEnv *env, jclass class) {
    cleanupRecorder();
}

JNIEXPORT jint Java_org_telegram_messenger_MediaController_isOpusFile(JNIEnv *env, jclass class, jstring path) {
    const char *pathStr = (*env)->GetStringUTFChars(env, path, 0);

    int32_t result = 0;

    int32_t error = OPUS_OK;
    OggOpusFile *file = op_test_file(pathStr, &error);
    if (file != NULL) {
        error = op_test_open(file);
        op_free(file);
        result = error == OPUS_OK;
    }

    if (pathStr != 0) {
        (*env)->ReleaseStringUTFChars(env, path, pathStr);
    }

    return result;
}

static inline void set_bits(uint8_t *bytes, int32_t bitOffset, int32_t value) {
    bytes += bitOffset / 8;
    bitOffset %= 8;

    uint16_t bits = (uint16_t) (value << bitOffset);
    bytes[0] |= (uint8_t) bits;
    bytes[1] |= (uint8_t) (bits >> 8);
}

JNIEXPORT jbyteArray Java_org_telegram_messenger_MediaController_getWaveform2(JNIEnv *env, jclass class, jshortArray array, jint length) {

    jshort *sampleBuffer = (*env)->GetShortArrayElements(env, array, 0);
    if (sampleBuffer == NULL) {
        return NULL;
    }

    const int32_t resultSamples = 100;
    uint16_t *samples = calloc(resultSamples, sizeof(uint16_t));
    if (samples == NULL) {
        (*env)->ReleaseShortArrayElements(env, array, sampleBuffer, JNI_ABORT);
        return NULL;
    }
    uint64_t sampleIndex = 0;
    uint16_t peakSample = 0;
    int32_t sampleRate = (int32_t) MAX(1, length / resultSamples);
    int32_t index = 0;

    for (int32_t i = 0; i < length; i++) {
        uint16_t sample = (uint16_t) abs(sampleBuffer[i]);
        if (sample > peakSample) {
            peakSample = sample;
        }
        if (sampleIndex++ % sampleRate == 0) {
            if (index < resultSamples) {
                samples[index++] = peakSample;
            }
            peakSample = 0;
        }
    }

    int64_t sumSamples = 0;
    for (int32_t i = 0; i < resultSamples; i++) {
        sumSamples += samples[i];
    }
    uint16_t peak = (uint16_t) (sumSamples * 1.8f / resultSamples);
    if (peak < 2500) {
        peak = 2500;
    }

    for (int32_t i = 0; i < resultSamples; i++) {
        uint16_t sample = (uint16_t) ((int64_t) samples[i]);
        if (sample > peak) {
            samples[i] = peak;
        }
    }

    (*env)->ReleaseShortArrayElements(env, array, sampleBuffer, JNI_ABORT);

    uint32_t bitstreamLength = resultSamples * 5 / 8 + 1;
    jbyteArray result = (*env)->NewByteArray(env, bitstreamLength);
    if (result) {
        uint8_t *bytes = calloc(bitstreamLength + 4, 1);
        if (bytes != NULL) {
            for (int32_t i = 0; i < resultSamples; i++) {
                int32_t value = MIN(31, abs((int32_t) samples[i]) * 31 / peak);
                set_bits(bytes, i * 5, value & 31);
            }
            (*env)->SetByteArrayRegion(env, result, 0, bitstreamLength, (jbyte *) bytes);
            free(bytes);
        } else {
            result = NULL;
        }
    }
    free(samples);

    return result;
}

JNIEXPORT jbyteArray Java_org_telegram_messenger_MediaController_getWaveform(JNIEnv *env, jclass class, jstring path) {
    const char *pathStr = (*env)->GetStringUTFChars(env, path, 0);
    if (pathStr == NULL) {
        return NULL;
    }
    jbyteArray result = 0;

    int error = OPUS_OK;
    OggOpusFile *opusFile = op_open_file(pathStr, &error);
    if (opusFile != NULL && error == OPUS_OK) {
        int64_t totalSamples = op_pcm_total(opusFile, -1);
        const uint32_t resultSamples = 100;
        int32_t sampleRate = MAX(1, (int32_t) (totalSamples / resultSamples));

        uint16_t *samples = calloc(resultSamples, sizeof(uint16_t));

        size_t bufferSize = 1024 * 128;
        int16_t *sampleBuffer = malloc(bufferSize);

        if (samples != NULL && sampleBuffer != NULL) {
            uint64_t sampleIndex = 0;
            uint16_t peakSample = 0;

            int32_t index = 0;
            int decodeFailed = 0;

            while (1) {
                int readSamples = op_read(opusFile, sampleBuffer, bufferSize / 2, NULL);
                if (readSamples < 0) {
                    LOGE("getWaveform: op_read failed: %d", readSamples);
                    decodeFailed = 1;
                    break;
                }
                if (readSamples == 0) {
                    break;
                }
                for (int32_t i = 0; i < readSamples; i++) {
                    uint16_t sample = (uint16_t) abs(sampleBuffer[i]);
                    if (sample > peakSample) {
                        peakSample = sample;
                    }
                    if (sampleIndex++ % sampleRate == 0) {
                        if (index < resultSamples) {
                            samples[index++] = peakSample;
                        }
                        peakSample = 0;
                    }
                }
            }

            if (!decodeFailed) {
                int64_t sumSamples = 0;
                for (int32_t i = 0; i < resultSamples; i++) {
                    sumSamples += samples[i];
                }
                uint16_t peak = (uint16_t) (sumSamples * 1.8f / resultSamples);
                if (peak < 2500) {
                    peak = 2500;
                }

                for (int32_t i = 0; i < resultSamples; i++) {
                    uint16_t sample = (uint16_t) ((int64_t) samples[i]);
                    if (sample > peak) {
                        samples[i] = peak;
                    }
                }

                uint32_t bitstreamLength = (resultSamples * 5) / 8 + 1;
                result = (*env)->NewByteArray(env, bitstreamLength);
                if (result) {
                    uint8_t *bytes = calloc(bitstreamLength + 4, 1);
                    if (bytes != NULL) {
                        for (int32_t i = 0; i < resultSamples; i++) {
                            int32_t value = MIN(31, abs((int32_t) samples[i]) * 31 / peak);
                            set_bits(bytes, i * 5, value & 31);
                        }

                        (*env)->SetByteArrayRegion(env, result, 0, bitstreamLength, (jbyte *) bytes);
                        free(bytes);
                    } else {
                        result = NULL;
                    }
                }
            }
        }

        free(sampleBuffer);
        free(samples);
        op_free(opusFile);
    }

    (*env)->ReleaseStringUTFChars(env, path, pathStr);
    return result;
}

JNIEXPORT void JNICALL Java_org_telegram_ui_Stories_recorder_FfmpegAudioWaveformLoader_init(JNIEnv *env, jobject obj, jstring pathJStr, jint count) {
    const char *path = (*env)->GetStringUTFChars(env, pathJStr, 0);
    if (path == NULL) {
        return;
    }
    if (count <= 0) {
        (*env)->ReleaseStringUTFChars(env, pathJStr, path);
        return;
    }

    AVFormatContext *formatContext = avformat_alloc_context();
    if (!formatContext) {
        (*env)->ReleaseStringUTFChars(env, pathJStr, path);
        return;
    }

    int res;
    if ((res = avformat_open_input(&formatContext, path, NULL, NULL)) != 0) {
        LOGD("avformat_open_input error %s", av_err2str(res));
        avformat_free_context(formatContext);
        (*env)->ReleaseStringUTFChars(env, pathJStr, path);
        return;
    }

    if (avformat_find_stream_info(formatContext, NULL) < 0) {
        avformat_close_input(&formatContext);
        (*env)->ReleaseStringUTFChars(env, pathJStr, path);
        return;
    }

    AVCodec *codec = NULL;
    int audioStreamIndex = av_find_best_stream(formatContext, AVMEDIA_TYPE_AUDIO, -1, -1, &codec, 0);
    if (audioStreamIndex < 0 || codec == NULL) {
        LOGD("av_find_best_stream error %s", av_err2str(audioStreamIndex));
        avformat_close_input(&formatContext);
        (*env)->ReleaseStringUTFChars(env, pathJStr, path);
        return;
    }

    AVCodecContext *codecContext = avcodec_alloc_context3(codec);
    if (codecContext == NULL) {
        avformat_close_input(&formatContext);
        (*env)->ReleaseStringUTFChars(env, pathJStr, path);
        return;
    }
    if (avcodec_parameters_to_context(codecContext, formatContext->streams[audioStreamIndex]->codecpar) < 0) {
        avcodec_free_context(&codecContext);
        avformat_close_input(&formatContext);
        (*env)->ReleaseStringUTFChars(env, pathJStr, path);
        return;
    }

    int64_t duration_in_microseconds = formatContext->duration;
    double duration_in_seconds = (double)duration_in_microseconds / AV_TIME_BASE;

    if (avcodec_open2(codecContext, codec, NULL) < 0) {
        avcodec_free_context(&codecContext);
        avformat_close_input(&formatContext);
        (*env)->ReleaseStringUTFChars(env, pathJStr, path);
        return;
    }

    jclass cls = (*env)->GetObjectClass(env, obj);
    jmethodID mid = (*env)->GetMethodID(env, cls, "receiveChunk", "([SI)V");

    AVFrame *frame = av_frame_alloc();
    if (frame == NULL) {
        avcodec_free_context(&codecContext);
        avformat_close_input(&formatContext);
        (*env)->ReleaseStringUTFChars(env, pathJStr, path);
        return;
    }
    AVPacket packet;

    int sampleRate = codecContext->sample_rate;
    int skip = 4;
    int barWidth = (int) round((double) duration_in_seconds * sampleRate / count / (1 + skip));

    int channels = codecContext->ch_layout.nb_channels;
    if (channels <= 0) {
        av_frame_free(&frame);
        avcodec_free_context(&codecContext);
        avformat_close_input(&formatContext);
        (*env)->ReleaseStringUTFChars(env, pathJStr, path);
        return;
    }

    short peak = 0;
    int currentCount = 0;
    int index = 0;
    int chunkIndex = 0;
    short waveformChunkData[32] = {0};

    while (av_read_frame(formatContext, &packet) >= 0) {
        if (packet.stream_index == audioStreamIndex) {
            int response = avcodec_send_packet(codecContext, &packet);

            while (response >= 0) {
                response = avcodec_receive_frame(codecContext, frame);
                if (response == AVERROR(EAGAIN) || response == AVERROR_EOF) {
                    break;
                } else if (response < 0) {
                    break;
                }

                const int is_planar = av_sample_fmt_is_planar(codecContext->sample_fmt);
                const int sample_size = av_get_bytes_per_sample(codecContext->sample_fmt);
                for (int i = 0; i < frame->nb_samples; i++) {
                    int sum = 0;
                    for (int channel = 0; channel < channels; channel++) {
                        uint8_t *data;
                        if (is_planar) {
                            data = frame->data[channel] + i * sample_size;
                        } else {
                            data = frame->data[0] + (i * channels + channel) * sample_size;
                        }
                        short sample_value = 0;
                        switch (codecContext->sample_fmt) {
                            case AV_SAMPLE_FMT_S16:
                            case AV_SAMPLE_FMT_S16P:
                                sample_value = *(int16_t *)data;
                                break;

                            case AV_SAMPLE_FMT_FLT:
                            case AV_SAMPLE_FMT_FLTP:
                                sample_value = (short)(*(float *)data * 32767.0f);
                                break;

                            case AV_SAMPLE_FMT_U8:
                            case AV_SAMPLE_FMT_U8P:
                                sample_value = (*(uint8_t *)data - 128) * 256;
                                break;

                            default:
                                break;
                        }
                        sum += sample_value;
                    }
                    short value = sum / channels;

                    if (currentCount >= barWidth) {
                        waveformChunkData[index - chunkIndex] = peak;
                        index++;
                        if (index - chunkIndex >= sizeof(waveformChunkData) / sizeof(short) || index >= count) {
                            jshortArray waveformData = (*env)->NewShortArray(env, sizeof(waveformChunkData) / sizeof(short));
                            if (waveformData == NULL) {
                                break;
                            }
                            (*env)->SetShortArrayRegion(env, waveformData, 0, sizeof(waveformChunkData) / sizeof(short), waveformChunkData);
                            (*env)->CallVoidMethod(env, obj, mid, waveformData, sizeof(waveformChunkData) / sizeof(short));

                            memset(waveformChunkData, 0, sizeof(waveformChunkData));
                            chunkIndex = index;

                            (*env)->DeleteLocalRef(env, waveformData);
                        }
                        peak = 0;
                        currentCount = 0;
                        if (index >= count) {
                            break;
                        }
                    }

                    if (peak < value) {
                        peak = value;
                    }
                    currentCount++;

                    i += skip;
                    if (i >= frame->nb_samples) {
                        break;
                    }
                }
            }
        }

        av_packet_unref(&packet);

        if (index >= count) {
            break;
        }

        jfieldID fid = (*env)->GetFieldID(env, cls, "running", "Z");
        jboolean running = (*env)->GetBooleanField(env, obj, fid);
        if (running == JNI_FALSE) {
            break;
        }
    }

    av_frame_free(&frame);
    avcodec_free_context(&codecContext);
    avformat_close_input(&formatContext);

    (*env)->ReleaseStringUTFChars(env, pathJStr, path);
}

static int writeOfflinePcmLocked(OggOpusFile *of, int16_t *buffer, int *buffered, opus_int64 remaining) {
    while (remaining > 0) {
        int capacity = (int) MIN(frame_size - *buffered, remaining);
        int samples = op_read(of, buffer + *buffered, capacity, NULL);
        if (samples <= 0) return 0;
        remaining -= samples;
        *buffered += samples;
        if (*buffered == frame_size) {
            if (!writeFrameLocked((uint8_t *) buffer, frame_size * sizeof(int16_t), 0)) return 0;
            *buffered = 0;
        }
    }
    return 1;
}

static int finishOfflinePcmLocked(int16_t *buffer, int buffered) {
    if (total_samples + buffered <= 0) return 0;
    int padding = header.preskip;
    do {
        int added = MIN(frame_size - buffered, padding);
        memset(buffer + buffered, 0, added * sizeof(int16_t));
        buffered += added;
        padding -= added;
        int end = padding == 0;
        if (end) total_samples -= header.preskip;
        if (!writeFrameLocked((uint8_t *) buffer, buffered * sizeof(int16_t), end)) return 0;
        buffered = 0;
    } while (padding > 0);
    return 1;
}

static int isMonoOpusFile(OggOpusFile *of) {
    int links = op_link_count(of);
    if (links <= 0) return 0;
    for (int i = 0; i < links; i++) {
        const OpusHead *head = op_head(of, i);
        if (!head || head->channel_count != 1) return 0;
    }
    return 1;
}

static int cropOpusAudioLocked(const char *inputPath, const char *outputPath, float startTimeMs, float endTimeMs) {
    if (!inputPath || !outputPath || !isfinite(startTimeMs) || !isfinite(endTimeMs)) return 0;
    int error;
    OggOpusFile *opusFile = op_open_file(inputPath, &error);
    if (!opusFile || error != OPUS_OK) {
        LOGE("Failed to open input opus file: %s", opus_strerror(error));
        return 0;
    }

    if (!isMonoOpusFile(opusFile)) {
        LOGE("Offline voice editing requires mono Opus input");
        op_free(opusFile);
        return 0;
    }

    opus_int64 total_source_samples = op_pcm_total(opusFile, -1);
    if (total_source_samples < 0) {
        LOGE("Failed to get source sample count: %lld", (long long) total_source_samples);
        op_free(opusFile);
        return 0;
    }

    opus_int32 rate = 48000;

    opus_int64 start_sample = MAX(0, MIN(total_source_samples, (opus_int64) ((startTimeMs / 1000.0) * rate)));
    opus_int64 end_sample = MAX(0, MIN(total_source_samples, (opus_int64) ((endTimeMs / 1000.0) * rate)));
    opus_int64 crop_length = end_sample - start_sample;

    if (start_sample >= total_source_samples || end_sample > total_source_samples || start_sample >= end_sample) {
        LOGE("Invalid crop range");
        op_free(opusFile);
        return 0;
    }

    if (op_pcm_seek(opusFile, start_sample) != 0) {
        LOGE("Failed to seek to start sample");
        op_free(opusFile);
        return 0;
    }

    if (!initRecorderLocked(outputPath, rate)) {
        LOGE("Failed to init recorder");
        op_free(opusFile);
        return 0;
    }

    int16_t *buffer = malloc(sizeof(int16_t) * frame_size);
    if (!buffer) {
        LOGE("Out of memory");
        cleanupRecorderLocked();
        op_free(opusFile);
        return 0;
    }

    int buffered = 0;
    int success = writeOfflinePcmLocked(opusFile, buffer, &buffered, crop_length)
            && finishOfflinePcmLocked(buffer, buffered);

    free(buffer);
    cleanupRecorderLocked();
    op_free(opusFile);

    return success;
}

static int append_stream(OggOpusFile *of, int16_t* buffer, int *buffered) {
    opus_int64 total_source_samples = op_pcm_total(of, -1);
    if (total_source_samples < 0) {
        LOGE("Failed to get source sample count: %lld",(long long) total_source_samples);
        return 0;
    }

    return writeOfflinePcmLocked(of, buffer, buffered, total_source_samples);
}

static int joinOpusAudiosLocked(const char* file1, const char* file2, const char* dest) {
    if (!file1 || !file2 || !dest) return 0;
    int error;
    OggOpusFile *opusFile1 = op_open_file(file1, &error);
    if (!opusFile1 || error != OPUS_OK) {
        LOGE("Failed to open input opus file1: %s", opus_strerror(error));
        return 0;
    }
    OggOpusFile *opusFile2 = op_open_file(file2, &error);
    if (!opusFile2 || error != OPUS_OK) {
        LOGE("Failed to open input opus file2: %s", opus_strerror(error));
        if (opusFile2 != NULL) {
            op_free(opusFile2);
        }
        op_free(opusFile1);
        return 0;
    }

    if (!isMonoOpusFile(opusFile1) || !isMonoOpusFile(opusFile2)) {
        LOGE("Offline voice editing requires mono Opus input");
        op_free(opusFile1);
        op_free(opusFile2);
        return 0;
    }

    opus_int32 rate = 48000;

    if (!initRecorderLocked(dest, rate)) {
        LOGE("Failed to init recorder");
        op_free(opusFile1);
        op_free(opusFile2);
        return 0;
    }

    int16_t *buffer = malloc(sizeof(int16_t) * frame_size);
    if (!buffer) {
        LOGE("Out of memory");
        cleanupRecorderLocked();
        op_free(opusFile1);
        op_free(opusFile2);
        return 0;
    }

    int buffered = 0;
    int success = append_stream(opusFile1, buffer, &buffered)
            && append_stream(opusFile2, buffer, &buffered)
            && finishOfflinePcmLocked(buffer, buffered);

    free(buffer);
    cleanupRecorderLocked();
    op_free(opusFile1);
    op_free(opusFile2);

    return success;
}

int cropOpusAudio(const char *inputPath, const char *outputPath, float startTimeMs, float endTimeMs) {
    pthread_mutex_lock(&recorderMutex);
    int result = recorderReady ? 0 : cropOpusAudioLocked(inputPath, outputPath, startTimeMs, endTimeMs);
    pthread_mutex_unlock(&recorderMutex);
    return result;
}

int joinOpusAudios(const char *file1, const char *file2, const char *dest) {
    pthread_mutex_lock(&recorderMutex);
    int result = recorderReady ? 0 : joinOpusAudiosLocked(file1, file2, dest);
    pthread_mutex_unlock(&recorderMutex);
    return result;
}

JNIEXPORT jboolean Java_org_telegram_messenger_MediaController_cropOpusFile(JNIEnv *env, jclass class, jstring src, jstring dst, jlong startMs, jlong endMs) {
    if (!src || !dst) return JNI_FALSE;
    const char* srcStr = (*env)->GetStringUTFChars(env, src, 0);
    if (!srcStr) return JNI_FALSE;
    const char* dstStr = (*env)->GetStringUTFChars(env, dst, 0);
    if (!dstStr) {
        (*env)->ReleaseStringUTFChars(env, src, srcStr);
        return JNI_FALSE;
    }
    int result = cropOpusAudio(srcStr, dstStr, startMs, endMs);
    (*env)->ReleaseStringUTFChars(env, src, srcStr);
    (*env)->ReleaseStringUTFChars(env, dst, dstStr);
    return result == 1;
}

JNIEXPORT jboolean Java_org_telegram_messenger_MediaController_joinOpusFiles(JNIEnv* env, jclass class, jstring file1, jstring file2, jstring dest) {
    if (!file1 || !file2 || !dest) return JNI_FALSE;
    const char* file1Str = (*env)->GetStringUTFChars(env, file1, 0);
    if (!file1Str) return JNI_FALSE;
    const char* file2Str = (*env)->GetStringUTFChars(env, file2, 0);
    if (!file2Str) {
        (*env)->ReleaseStringUTFChars(env, file1, file1Str);
        return JNI_FALSE;
    }
    const char* destStr = (*env)->GetStringUTFChars(env, dest, 0);
    if (!destStr) {
        (*env)->ReleaseStringUTFChars(env, file2, file2Str);
        (*env)->ReleaseStringUTFChars(env, file1, file1Str);
        return JNI_FALSE;
    }
    int result = joinOpusAudios(file1Str, file2Str, destStr);
    (*env)->ReleaseStringUTFChars(env, file1, file1Str);
    (*env)->ReleaseStringUTFChars(env, file2, file2Str);
    (*env)->ReleaseStringUTFChars(env, dest, destStr);
    return result == 1;
}

#include <stdint.h>

typedef struct {
    DenoiseState *state;
    float *input;
    float *output;
    int frame_size;
} AudioNoiseSuppressorContext;

static void destroyAudioNoiseSuppressorContext(AudioNoiseSuppressorContext *context) {
    if (!context) {
        return;
    }
    if (context->state) {
        rnnoise_destroy(context->state);
    }
    free(context->input);
    free(context->output);
    free(context);
}

static AudioNoiseSuppressorContext *createAudioNoiseSuppressorContext() {
    AudioNoiseSuppressorContext *context = calloc(1, sizeof(AudioNoiseSuppressorContext));
    if (!context) {
        return NULL;
    }
    context->frame_size = rnnoise_get_frame_size();
    if (context->frame_size <= 0) {
        destroyAudioNoiseSuppressorContext(context);
        return NULL;
    }
    context->input = malloc(sizeof(float) * context->frame_size);
    context->output = malloc(sizeof(float) * context->frame_size);
    context->state = rnnoise_create(NULL);
    if (!context->input || !context->output || !context->state) {
        destroyAudioNoiseSuppressorContext(context);
        return NULL;
    }
    return context;
}

static int processAudioNoiseSuppressor(
        AudioNoiseSuppressorContext *context,
        int16_t *samples,
        int sampleCount
) {
    if (!context || !samples || sampleCount < 0) {
        return 0;
    }
    int sampleOffset = 0;
    while (sampleOffset < sampleCount) {
        int blockSamples = MIN(context->frame_size, sampleCount - sampleOffset);
        for (int index = 0; index < blockSamples; index++) {
            context->input[index] = samples[sampleOffset + index];
        }
        for (int index = blockSamples; index < context->frame_size; index++) {
            context->input[index] = 0.0f;
        }
        rnnoise_process_frame(context->state, context->output, context->input);
        for (int index = 0; index < blockSamples; index++) {
            if (!isfinite(context->output[index])) {
                return 0;
            }
        }
        for (int index = 0; index < blockSamples; index++) {
            float sample = fmaxf(-32768.0f, fminf(32767.0f, context->output[index]));
            samples[sampleOffset + index] = (int16_t) lrintf(sample);
        }
        sampleOffset += blockSamples;
    }
    return 1;
}

typedef struct {
    opus_int32 rate;
    opus_int32 coding_rate;
    ogg_int32_t packet_id;
    OpusEncoder *encoder;
    uint8_t *packet;
    ogg_stream_state stream;
    int stream_initialized;
    FILE *file;
    oe_enc_opt options;
    OpusHeader header;
    opus_int32 min_bytes;
    int max_frame_bytes;
    ogg_packet ogg_packet;
    ogg_page page;
    opus_int64 bytes_written;
    opus_int64 pages_out;
    opus_int64 total_samples;
    ogg_int64_t encoded_granule_position;
    ogg_int64_t last_granule_position;
    int last_segments;
    int16_t pending_frame[960];
    int pending_samples;
} AudioRecorderContext;

static volatile int32_t recorder_serial = 1;

static void destroyAudioRecorderContext(AudioRecorderContext *recorder) {
    if (!recorder) {
        return;
    }

    if (recorder->stream_initialized) {
        while (ogg_stream_flush(&recorder->stream, &recorder->page)) {
            if (recorder->file) {
                writeOggPage(&recorder->page, recorder->file);
            }
        }
        ogg_stream_clear(&recorder->stream);
    }
    if (recorder->encoder) {
        opus_encoder_destroy(recorder->encoder);
    }
    if (recorder->packet) {
        free(recorder->packet);
    }
    if (recorder->options.comments) {
        free(recorder->options.comments);
    }
    if (recorder->file) {
        fclose(recorder->file);
    }
    free(recorder);
}

static AudioRecorderContext *createAudioRecorderContext(const char *path, opus_int32 sampleRate) {
    if (!path) {
        LOGE("path is null");
        return NULL;
    }

    AudioRecorderContext *recorder = calloc(1, sizeof(AudioRecorderContext));
    if (!recorder) {
        LOGE("Unable to allocate audio recorder context");
        return NULL;
    }

    recorder->packet_id = -1;
    recorder->coding_rate = sampleRate;
    recorder->rate = sampleRate;

    recorder->file = fopen(path, "w");
    if (!recorder->file) {
        LOGE("error cannot open file: %s", path);
        destroyAudioRecorderContext(recorder);
        return NULL;
    }

    recorder->options.rate = recorder->rate;
    recorder->options.gain = 0;
    recorder->options.endianness = 0;
    recorder->options.copy_comments = 0;
    recorder->options.rawmode = 0;
    recorder->options.ignorelength = 0;
    recorder->options.samplesize = 16;
    recorder->options.channels = 1;
    recorder->options.skip = 0;

    comment_init(
            &recorder->options.comments,
            &recorder->options.comments_length,
            opus_get_version_string()
    );
    if (!recorder->options.comments) {
        LOGE("Unable to allocate Opus comments");
        destroyAudioRecorderContext(recorder);
        return NULL;
    }

    if (recorder->rate != recorder->coding_rate) {
        LOGE("Invalid rate");
        destroyAudioRecorderContext(recorder);
        return NULL;
    }

    recorder->header.channels = 1;
    recorder->header.channel_mapping = 0;
    recorder->header.input_sample_rate = recorder->rate;
    recorder->header.gain = recorder->options.gain;
    recorder->header.nb_streams = 1;

    int result = OPUS_OK;
    recorder->encoder = opus_encoder_create(
            recorder->coding_rate,
            1,
            opus_application,
            &result
    );
    if (result != OPUS_OK) {
        LOGE("Error cannot create encoder: %s", opus_strerror(result));
        destroyAudioRecorderContext(recorder);
        return NULL;
    }

    recorder->min_bytes = recorder->max_frame_bytes =
            (1275 * 3 + 7) * recorder->header.nb_streams;
    recorder->packet = malloc(recorder->max_frame_bytes);
    if (!recorder->packet) {
        LOGE("Unable to allocate Opus packet buffer");
        destroyAudioRecorderContext(recorder);
        return NULL;
    }

    result = opus_encoder_ctl(recorder->encoder, OPUS_SET_BITRATE(bitrate));
    if (result != OPUS_OK) {
        LOGE("Error OPUS_SET_BITRATE returned: %s", opus_strerror(result));
        destroyAudioRecorderContext(recorder);
        return NULL;
    }

#ifdef OPUS_SET_LSB_DEPTH
    result = opus_encoder_ctl(
            recorder->encoder,
            OPUS_SET_LSB_DEPTH(MAX(8, MIN(24, recorder->options.samplesize)))
    );
    if (result != OPUS_OK) {
        LOGE("Warning OPUS_SET_LSB_DEPTH returned: %s", opus_strerror(result));
    }
#endif

    opus_int32 lookahead;
    result = opus_encoder_ctl(recorder->encoder, OPUS_GET_LOOKAHEAD(&lookahead));
    if (result != OPUS_OK) {
        LOGE("Error OPUS_GET_LOOKAHEAD returned: %s", opus_strerror(result));
        destroyAudioRecorderContext(recorder);
        return NULL;
    }

    recorder->options.skip += lookahead;
    recorder->header.preskip = (int) (
            recorder->options.skip * (48000.0 / recorder->coding_rate)
    );
    recorder->options.extraout = (int) (
            recorder->header.preskip * (recorder->rate / 48000.0)
    );

    int serial = __sync_fetch_and_add(&recorder_serial, 1)
            ^ (int) time(NULL)
            ^ (int) (uintptr_t) recorder;
    if (ogg_stream_init(&recorder->stream, serial) == -1) {
        LOGE("Error: stream init failed");
        destroyAudioRecorderContext(recorder);
        return NULL;
    }
    recorder->stream_initialized = 1;

    unsigned char header_data[100];
    int packet_size = opus_header_to_packet(&recorder->header, header_data, 100);
    recorder->ogg_packet.packet = header_data;
    recorder->ogg_packet.bytes = packet_size;
    recorder->ogg_packet.b_o_s = 1;
    recorder->ogg_packet.e_o_s = 0;
    recorder->ogg_packet.granulepos = 0;
    recorder->ogg_packet.packetno = 0;
    ogg_stream_packetin(&recorder->stream, &recorder->ogg_packet);

    while ((result = ogg_stream_flush(&recorder->stream, &recorder->page))) {
        if (!result) {
            break;
        }

        int pageBytesWritten = writeOggPage(&recorder->page, recorder->file);
        if (pageBytesWritten != recorder->page.header_len + recorder->page.body_len) {
            LOGE("Error: failed writing header to output stream");
            destroyAudioRecorderContext(recorder);
            return NULL;
        }
        recorder->bytes_written += pageBytesWritten;
        recorder->pages_out++;
    }

    comment_pad(
            &recorder->options.comments,
            &recorder->options.comments_length,
            comment_padding
    );
    recorder->ogg_packet.packet = (unsigned char *) recorder->options.comments;
    recorder->ogg_packet.bytes = recorder->options.comments_length;
    recorder->ogg_packet.b_o_s = 0;
    recorder->ogg_packet.e_o_s = 0;
    recorder->ogg_packet.granulepos = 0;
    recorder->ogg_packet.packetno = 1;
    ogg_stream_packetin(&recorder->stream, &recorder->ogg_packet);

    while ((result = ogg_stream_flush(&recorder->stream, &recorder->page))) {
        if (result == 0) {
            break;
        }

        int writtenPageBytes = writeOggPage(&recorder->page, recorder->file);
        if (writtenPageBytes != recorder->page.header_len + recorder->page.body_len) {
            LOGE("Error: failed writing header to output stream");
            destroyAudioRecorderContext(recorder);
            return NULL;
        }

        recorder->bytes_written += writtenPageBytes;
        recorder->pages_out++;
    }

    free(recorder->options.comments);
    recorder->options.comments = NULL;

    return recorder;
}

static int writeAudioRecorderContextPacket(
        AudioRecorderContext *recorder,
        uint8_t *framePcmBytes,
        uint32_t frameByteCount,
        opus_int32 countedSamples,
        int end
) {
    if (!recorder || !recorder->encoder || !recorder->file
            || (frameByteCount & 1) || frameByteCount > frame_size * 2
            || (frameByteCount > 0 && !framePcmBytes)) {
        return 0;
    }
    size_t cur_frame_size = frame_size;
    recorder->packet_id++;

    opus_int32 nb_samples = frameByteCount / 2;
    recorder->total_samples += countedSamples;
    recorder->ogg_packet.e_o_s = end;

    int nbBytes = 0;
    int size_segments = 0;

    if (nb_samples != 0) {
        uint8_t *paddedFrameBytes = framePcmBytes;
        int freePaddedFrameBytes = 0;

        if (nb_samples < cur_frame_size) {
            paddedFrameBytes = malloc(cur_frame_size * 2);
            if (!paddedFrameBytes) return 0;
            freePaddedFrameBytes = 1;
            memcpy(paddedFrameBytes, framePcmBytes, frameByteCount);
            memset(paddedFrameBytes + nb_samples * 2, 0, cur_frame_size * 2 - nb_samples * 2);
        }

        nbBytes = opus_encode(
                recorder->encoder,
                (opus_int16 *) paddedFrameBytes,
                cur_frame_size,
                recorder->packet,
                recorder->max_frame_bytes / 10
        );
        if (freePaddedFrameBytes) {
            free(paddedFrameBytes);
        }

        if (nbBytes < 0) {
            LOGE("Encoding failed: %s. Aborting.", opus_strerror(nbBytes));
            return 0;
        }

        recorder->encoded_granule_position +=
                cur_frame_size * 48000 / recorder->coding_rate;
        size_segments = (nbBytes + 255) / 255;
        recorder->min_bytes = MIN(nbBytes, recorder->min_bytes);
    }

    while ((((size_segments <= 255)
            && (recorder->last_segments + size_segments > 255))
            || (recorder->encoded_granule_position
            - recorder->last_granule_position > max_ogg_delay))
            && ogg_stream_flush_fill(
                    &recorder->stream,
                    &recorder->page,
                    255 * 255
            )) {
        if (ogg_page_packets(&recorder->page) != 0) {
            recorder->last_granule_position = ogg_page_granulepos(&recorder->page);
        }

        recorder->last_segments -= recorder->page.header[26];
        int writtenPageBytes = writeOggPage(&recorder->page, recorder->file);
        if (writtenPageBytes != recorder->page.header_len + recorder->page.body_len) {
            LOGE("Error: failed writing data to output stream");
            return 0;
        }
        recorder->bytes_written += writtenPageBytes;
        recorder->pages_out++;
    }

    recorder->ogg_packet.packet = recorder->packet;
    recorder->ogg_packet.bytes = nbBytes;
    recorder->ogg_packet.b_o_s = 0;
    recorder->ogg_packet.granulepos = recorder->encoded_granule_position;
    if (recorder->ogg_packet.e_o_s) {
        recorder->ogg_packet.granulepos = (
                (recorder->total_samples * 48000 + recorder->rate - 1)
                / recorder->rate
        ) + recorder->header.preskip;
    }
    recorder->ogg_packet.packetno = 2 + recorder->packet_id;
    ogg_stream_packetin(&recorder->stream, &recorder->ogg_packet);
    recorder->last_segments += size_segments;

    while ((recorder->ogg_packet.e_o_s
            || (recorder->encoded_granule_position
            + (frame_size * 48000 / recorder->coding_rate)
            - recorder->last_granule_position > max_ogg_delay)
            || (recorder->last_segments >= 255))
            ? ogg_stream_flush_fill(&recorder->stream, &recorder->page, 255 * 255)
            : ogg_stream_pageout_fill(&recorder->stream, &recorder->page, 255 * 255)) {
        if (ogg_page_packets(&recorder->page) != 0) {
            recorder->last_granule_position = ogg_page_granulepos(&recorder->page);
        }
        recorder->last_segments -= recorder->page.header[26];
        int writtenPageBytes = writeOggPage(&recorder->page, recorder->file);
        if (writtenPageBytes != recorder->page.header_len + recorder->page.body_len) {
            LOGE("Error: failed writing data to output stream");
            return 0;
        }
        recorder->bytes_written += writtenPageBytes;
        recorder->pages_out++;
    }

    return 1;
}

static int writeAudioRecorderContextFrame(
        AudioRecorderContext *recorder,
        uint8_t *framePcmBytes,
        uint32_t frameByteCount,
        int end
) {
    return writeAudioRecorderContextPacket(
            recorder,
            framePcmBytes,
            frameByteCount,
            (opus_int32) (frameByteCount / 2),
            end
    );
}

static int finishAudioRecorderStream(AudioRecorderContext *recorder) {
    if (!recorder) {
        return 0;
    }
    if (recorder->pending_samples > 0) {
        int pendingSamples = recorder->pending_samples;
        ogg_int64_t tailEndGranulePosition =
                ((recorder->total_samples + pendingSamples) * 48000 + recorder->rate - 1)
                / recorder->rate + recorder->header.preskip;
        int end = recorder->encoded_granule_position
                + frame_size * 48000 / recorder->coding_rate >= tailEndGranulePosition;
        recorder->pending_samples = 0;
        if (!writeAudioRecorderContextFrame(
                recorder,
                (uint8_t *) recorder->pending_frame,
                pendingSamples * sizeof(int16_t),
                end
        )) {
            return 0;
        }
        if (end) return 1;
    }
    ogg_int64_t endGranulePosition =
            (recorder->total_samples * 48000 + recorder->rate - 1) / recorder->rate
            + recorder->header.preskip;
    int16_t silence[960];
    memset(silence, 0, sizeof(silence));
    while (1) {
        int end = recorder->encoded_granule_position
                + frame_size * 48000 / recorder->coding_rate >= endGranulePosition;
        if (!writeAudioRecorderContextPacket(
                recorder,
                (uint8_t *) silence,
                sizeof(silence),
                0,
                end
        )) {
            return 0;
        }
        if (end) {
            return 1;
        }
    }
}

static jlong flushAudioRecorderContext(AudioRecorderContext *recorder) {
    if (!recorder || !recorder->file || fflush(recorder->file) != 0) {
        return -1;
    }
    long position = ftell(recorder->file);
    return position < 0 ? -1 : (jlong) position;
}

static int writeAudioRecorderFrame(AudioRecorderContext *recorder, uint8_t *pcmBytes, uint32_t byteCount);

static int cropAudioRecorderOpusAudio(const char *inputPath, const char *outputPath, float startTimeMs, float endTimeMs) {
    if (!inputPath || !outputPath || strcmp(inputPath, outputPath) == 0
            || !isfinite(startTimeMs) || !isfinite(endTimeMs)
            || startTimeMs < 0 || endTimeMs <= startTimeMs) return 0;
    int error;
    OggOpusFile *opusFile = op_open_file(inputPath, &error);
    if (!opusFile || error != OPUS_OK) {
        LOGE("Failed to open input opus file: %s", opus_strerror(error));
        return 0;
    }

    const OpusHead *head = op_head(opusFile, -1);
    if (!isMonoOpusFile(opusFile)) {
        op_free(opusFile);
        return 0;
    }
    if (!head) {
        LOGE("Failed to read Opus header");
        op_free(opusFile);
        return 0;
    }

    int channels = head->channel_count;
    opus_int64 total_source_samples = op_pcm_total(opusFile, -1);
    if (total_source_samples < 0) {
        LOGE("Failed to get source sample count: %lld", (long long) total_source_samples);
        op_free(opusFile);
        return 0;
    }

    opus_int32 rate = 48000;

    opus_int64 start_sample = MAX(0, MIN(total_source_samples, (opus_int64) ((startTimeMs / 1000.0) * rate)));
    opus_int64 end_sample = MAX(0, MIN(total_source_samples, (opus_int64) ((endTimeMs / 1000.0) * rate)));
    opus_int64 crop_length = end_sample - start_sample;

    if (start_sample >= total_source_samples || end_sample > total_source_samples || start_sample >= end_sample) {
        LOGE("Invalid crop range");
        op_free(opusFile);
        return 0;
    }

    if (op_pcm_seek(opusFile, start_sample) != 0) {
        LOGE("Failed to seek to start sample");
        op_free(opusFile);
        return 0;
    }

    AudioRecorderContext *recorder = createAudioRecorderContext(outputPath, rate);
    if (!recorder) {
        LOGE("Failed to init recorder");
        op_free(opusFile);
        return 0;
    }

    int16_t *buffer = malloc(sizeof(int16_t) * frame_size * channels);
    if (!buffer) {
        LOGE("Out of memory");
        destroyAudioRecorderContext(recorder);
        op_free(opusFile);
        return 0;
    }

    int success = 1;
    opus_int64 remaining_samples = crop_length;
    while (remaining_samples > 0) {
        int max_samples = (int) MIN(960, remaining_samples / channels);
        if (max_samples <= 0) {
            break;
        }
        int samples_read = op_read(opusFile, buffer, max_samples * channels, NULL);
        if (samples_read < 0) {
            LOGE("Decoding error from op_read(): %d", samples_read);
            success = 0;
            break;
        }
        if (samples_read == 0) {
            break;
        }
        remaining_samples -= samples_read;

        size_t byte_count = samples_read * sizeof(int16_t);
        if (!writeAudioRecorderFrame(recorder, (uint8_t *)buffer, byte_count)) {
            LOGE("Failed to write frame");
            success = 0;
            break;
        }
    }
    if (success && !finishAudioRecorderStream(recorder)) {
        LOGE("Failed to finish cropped stream");
        success = 0;
    }

    free(buffer);
    destroyAudioRecorderContext(recorder);
    op_free(opusFile);

    return success;
}

static int appendRecorderPrefix(
        AudioRecorderContext *recorder,
        const char *prefixPath,
        opus_int64 startMs,
        opus_int64 endMs
) {
    if (!recorder || !prefixPath || startMs < 0 || startMs > INT64_MAX / 48000
            || endMs > INT64_MAX / 48000) {
        return 0;
    }

    int error = OPUS_OK;
    OggOpusFile *prefixFile = op_open_file(prefixPath, &error);
    if (!prefixFile || error != OPUS_OK) {
        LOGE("Failed to open Opus prefix: %s", opus_strerror(error));
        if (prefixFile) {
            op_free(prefixFile);
        }
        return 0;
    }

    const OpusHead *head = op_head(prefixFile, -1);
    if (!head || !isMonoOpusFile(prefixFile)) {
        LOGE("Unsupported Opus prefix channel count");
        op_free(prefixFile);
        return 0;
    }

    opus_int64 remainingSamples = INT64_MAX;
    if (startMs > 0 || endMs >= 0) {
        opus_int64 totalSamples = op_pcm_total(prefixFile, -1);
        if (totalSamples < 0) {
            LOGE("Failed to get Opus prefix length: %lld", (long long) totalSamples);
            op_free(prefixFile);
            return 0;
        }
        opus_int64 startSample = MAX(0, MIN(totalSamples, startMs * 48000 / 1000));
        opus_int64 endSample = endMs < 0
                ? totalSamples
                : MAX(startSample, MIN(totalSamples, endMs * 48000 / 1000));
        if (startSample > 0 && op_pcm_seek(prefixFile, startSample) != 0) {
            LOGE("Failed to seek Opus prefix to %lld", (long long) startSample);
            op_free(prefixFile);
            return 0;
        }
        remainingSamples = endSample - startSample;
    }

    int16_t *buffer = malloc(sizeof(int16_t) * frame_size);
    if (!buffer) {
        LOGE("Out of memory while rebuilding Opus prefix");
        op_free(prefixFile);
        return 0;
    }

    int success = 1;
    int bufferedSamples = 0;
    while (remainingSamples > 0) {
        int samplesRead = op_read(
                prefixFile,
                buffer + bufferedSamples,
                (int) MIN(frame_size - bufferedSamples, remainingSamples),
                NULL
        );
        if (samplesRead < 0) {
            LOGE("Failed to decode Opus prefix: %d", samplesRead);
            success = 0;
            break;
        }
        if (samplesRead == 0) {
            break;
        }
        remainingSamples -= samplesRead;
        bufferedSamples += samplesRead;
        if (bufferedSamples == frame_size) {
            if (!writeAudioRecorderContextFrame(
                    recorder,
                    (uint8_t *) buffer,
                    frame_size * sizeof(int16_t),
                    0
            )) {
                success = 0;
                break;
            }
            bufferedSamples = 0;
        }
    }

    if (success && bufferedSamples > 0) {
        memcpy(
                recorder->pending_frame,
                buffer,
                bufferedSamples * sizeof(int16_t)
        );
        recorder->pending_samples = bufferedSamples;
    }
    free(buffer);
    op_free(prefixFile);
    return success;
}

static int writeAudioRecorderFrame(
        AudioRecorderContext *recorder,
        uint8_t *pcmBytes,
        uint32_t byteCount
) {
    if (!recorder || (byteCount > 0 && !pcmBytes)) {
        return 0;
    }

    int16_t *samples = (int16_t *) pcmBytes;
    int sampleCount = (int) byteCount / 2;
    int sampleOffset = 0;

    while (sampleOffset < sampleCount) {
        if (recorder->pending_samples == 0
                && sampleCount - sampleOffset >= frame_size) {
            if (!writeAudioRecorderContextFrame(
                    recorder,
                    (uint8_t *) (samples + sampleOffset),
                    frame_size * sizeof(int16_t),
                    0
            )) {
                return 0;
            }
            sampleOffset += frame_size;
            continue;
        }

        int copySamples = MIN(
                frame_size - recorder->pending_samples,
                sampleCount - sampleOffset
        );
        memcpy(
                recorder->pending_frame + recorder->pending_samples,
                samples + sampleOffset,
                copySamples * sizeof(int16_t)
        );
        recorder->pending_samples += copySamples;
        sampleOffset += copySamples;

        if (recorder->pending_samples == frame_size) {
            if (!writeAudioRecorderContextFrame(
                    recorder,
                    (uint8_t *) recorder->pending_frame,
                    frame_size * sizeof(int16_t),
                    0
            )) {
                return 0;
            }
            recorder->pending_samples = 0;
        }
    }
    return 1;
}

JNIEXPORT jlong Java_org_telegram_utils_recorder_audio_AudioRecorderNative_nCreate(
        JNIEnv *env,
        jclass clazz,
        jstring path,
        jint sampleRate,
        jstring prefixPath,
        jlong prefixStartMs,
        jlong prefixEndMs
) {
    if (!path) return 0;
    const char *pathString = (*env)->GetStringUTFChars(env, path, 0);
    if (!pathString) {
        return 0;
    }

    AudioRecorderContext *recorder = createAudioRecorderContext(pathString, sampleRate);
    (*env)->ReleaseStringUTFChars(env, path, pathString);
    if (!recorder) {
        return 0;
    }

    if (prefixPath) {
        const char *prefixPathString = (*env)->GetStringUTFChars(env, prefixPath, 0);
        if (!prefixPathString) {
            destroyAudioRecorderContext(recorder);
            return 0;
        }
        int prefixResult = appendRecorderPrefix(
                recorder,
                prefixPathString,
                prefixStartMs,
                prefixEndMs
        );
        (*env)->ReleaseStringUTFChars(env, prefixPath, prefixPathString);
        if (!prefixResult) {
            destroyAudioRecorderContext(recorder);
            return 0;
        }
    }

    return (jlong) (intptr_t) recorder;
}

JNIEXPORT jint Java_org_telegram_utils_recorder_audio_AudioRecorderNative_nWriteFrame(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jobject frame,
        jint length
) {
    AudioRecorderContext *recorder = (AudioRecorderContext *) (intptr_t) handle;
    if (!recorder || !frame || length < 0 || (length & 1)) return 0;
    jbyte *frameBytes = (*env)->GetDirectBufferAddress(env, frame);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, frame);
    if (!recorder || !frameBytes || length < 0 || capacity < length) {
        return 0;
    }
    return writeAudioRecorderFrame(recorder, (uint8_t *) frameBytes, (uint32_t) length);
}

JNIEXPORT jlong Java_org_telegram_utils_recorder_audio_AudioRecorderNative_nFlush(
        JNIEnv *env,
        jclass clazz,
        jlong handle
) {
    AudioRecorderContext *recorder = (AudioRecorderContext *) (intptr_t) handle;
    return flushAudioRecorderContext(recorder);
}

JNIEXPORT jboolean Java_org_telegram_utils_recorder_audio_AudioRecorderNative_nFinish(
        JNIEnv *env,
        jclass clazz,
        jlong handle
) {
    AudioRecorderContext *recorder = (AudioRecorderContext *) (intptr_t) handle;
    if (!recorder) {
        return JNI_FALSE;
    }

    int result = finishAudioRecorderStream(recorder);
    if (result && flushAudioRecorderContext(recorder) < 0) {
        result = 0;
    }
    destroyAudioRecorderContext(recorder);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void Java_org_telegram_utils_recorder_audio_AudioRecorderNative_nDestroy(
        JNIEnv *env,
        jclass clazz,
        jlong handle
) {
    destroyAudioRecorderContext((AudioRecorderContext *) (intptr_t) handle);
}

JNIEXPORT jboolean Java_org_telegram_utils_recorder_audio_AudioRecorderNative_nCropOpusFile(
        JNIEnv *env,
        jclass clazz,
        jstring source,
        jstring destination,
        jlong startMs,
        jlong endMs
) {
    if (!source || !destination) return JNI_FALSE;
    const char *sourceString = (*env)->GetStringUTFChars(env, source, 0);
    if (!sourceString) return JNI_FALSE;
    const char *destinationString = (*env)->GetStringUTFChars(env, destination, 0);
    if (!destinationString) {
        (*env)->ReleaseStringUTFChars(env, source, sourceString);
        return JNI_FALSE;
    }
    int result = cropAudioRecorderOpusAudio(sourceString, destinationString, startMs, endMs);
    (*env)->ReleaseStringUTFChars(env, source, sourceString);
    (*env)->ReleaseStringUTFChars(env, destination, destinationString);
    return result == 1;
}

JNIEXPORT jbyteArray Java_org_telegram_utils_recorder_audio_AudioRecorderNative_nGetWaveform(
        JNIEnv *env,
        jclass clazz,
        jstring path
) {
    return Java_org_telegram_messenger_MediaController_getWaveform(env, clazz, path);
}

JNIEXPORT jlong Java_org_telegram_utils_recorder_audio_AudioNoiseSuppressor_nCreate(
        JNIEnv *env,
        jclass clazz
) {
    return (jlong) (intptr_t) createAudioNoiseSuppressorContext();
}

JNIEXPORT jboolean Java_org_telegram_utils_recorder_audio_AudioNoiseSuppressor_nProcess(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jobject pcm,
        jint sampleCount
) {
    AudioNoiseSuppressorContext *context =
            (AudioNoiseSuppressorContext *) (intptr_t) handle;
    int16_t *samples = (*env)->GetDirectBufferAddress(env, pcm);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, pcm);
    if (!context || !samples || sampleCount < 0
            || capacity < (jlong) sampleCount * sizeof(int16_t)) {
        return JNI_FALSE;
    }
    return processAudioNoiseSuppressor(context, samples, sampleCount)
            ? JNI_TRUE
            : JNI_FALSE;
}

JNIEXPORT void Java_org_telegram_utils_recorder_audio_AudioNoiseSuppressor_nDestroy(
        JNIEnv *env,
        jclass clazz,
        jlong handle
) {
    destroyAudioNoiseSuppressorContext(
            (AudioNoiseSuppressorContext *) (intptr_t) handle
    );
}
