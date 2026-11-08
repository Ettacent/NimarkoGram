/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include <functional>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/time.h>
}

class VideoFrameReader {
public:
    enum class Status {
        Ok,
        Eof,
        Again,
        Aborted,
        Error,
        Yield,
    };

    struct Budget {
        int steps;
        int64_t micros;
        int64_t started = av_gettime_relative();

        bool take() {
            if (steps <= 0 || av_gettime_relative() - started >= micros) return false;
            --steps;
            return true;
        }
    };

    VideoFrameReader(AVFormatContext *fmt, AVCodecContext *dec, int streamIndex)
            : m_fmt(fmt), m_dec(dec), m_streamIndex(streamIndex) {
        m_pkt = av_packet_alloc();
        m_frame = av_frame_alloc();
    }

    ~VideoFrameReader() {
        av_frame_free(&m_frame);
        av_packet_free(&m_pkt);
    }

    VideoFrameReader(const VideoFrameReader &) = delete;
    VideoFrameReader &operator=(const VideoFrameReader &) = delete;

    std::function<bool()> shouldAbort;

    Status getNextFrame(Budget *budget = nullptr) {
        if (m_pkt == nullptr || m_frame == nullptr) {
            return Status::Error;
        }

        av_frame_unref(m_frame);

        for (;;) {
            if (shouldAbort && shouldAbort()) {
                return Status::Aborted;
            }

            if (!m_needFeed) {
                if (budget && !budget->take()) return Status::Yield;
                int ret = avcodec_receive_frame(m_dec, m_frame);
                if (ret == 0) {
                    return Status::Ok;
                }
                if (ret == AVERROR_EOF) {
                    return Status::Eof;
                }
                if (ret != AVERROR(EAGAIN)) {
                    return Status::Error;
                }

                if (m_draining) {
                    return budget ? Status::Error : Status::Eof;
                }
                m_needFeed = true;
            }
            switch (feedNextPacket(budget)) {
                case FeedResult::Sent:
                case FeedResult::NeedReceive:
                    m_needFeed = false;
                    break;
                case FeedResult::Eof:
                    m_needFeed = false;
                    return Status::Eof;
                case FeedResult::Again:
                    if (!budget) m_needFeed = false;
                    return Status::Again;
                case FeedResult::Aborted:
                    if (!budget) m_needFeed = false;
                    return Status::Aborted;
                case FeedResult::Error:
                    if (!budget) m_needFeed = false;
                    return Status::Error;
                case FeedResult::Yield:
                    return Status::Yield;
            }
        }
    }

    bool seek(int64_t pts,
              int flags = AVSEEK_FLAG_BACKWARD) {
        int ret = av_seek_frame(m_fmt, m_streamIndex, pts, flags);
        if (ret < 0) {
            return false;
        }
        avcodec_flush_buffers(m_dec);
        av_frame_unref(m_frame);
        av_packet_unref(m_pkt);
        m_packetPending = false;
        m_draining = false;
        m_flushPending = false;
        m_needFeed = false;
        return true;
    }

    AVFrame *frame() const { return m_frame; }

    double frameTimeSeconds() const {
        AVRational tb = m_fmt->streams[m_streamIndex]->time_base;
        return m_frame->best_effort_timestamp * av_q2d(tb);
    }

    int streamIndex() const { return m_streamIndex; }
    bool valid() const { return m_pkt != nullptr && m_frame != nullptr; }

private:
    enum class FeedResult {
        Sent,
        NeedReceive,
        Eof,
        Again,
        Aborted,
        Error,
        Yield,
    };

    FeedResult feedNextPacket(Budget *budget) {
        const int64_t retryStartedAt = av_gettime_relative();
        int readRetries = 0;
        for (;;) {
            if (shouldAbort && shouldAbort()) return FeedResult::Aborted;
            if (!m_packetPending && !m_flushPending) {
                if (budget && !budget->take()) return FeedResult::Yield;
                int ret = av_read_frame(m_fmt, m_pkt);
                if (ret >= 0) {
                    if (m_pkt->stream_index == m_streamIndex) m_packetPending = true;
                    else av_packet_unref(m_pkt);
                }
                if (shouldAbort && shouldAbort()) return FeedResult::Aborted;
                if (ret == AVERROR(EAGAIN)) {
                    if (shouldAbort && shouldAbort()) {
                        return FeedResult::Aborted;
                    }
                    if (budget) return FeedResult::Again;
                    if (av_gettime_relative() - retryStartedAt >= 50000) {
                        return FeedResult::Again;
                    }
                    ++readRetries;
                    av_usleep(1000L << (readRetries < 5 ? readRetries - 1 : 4));
                    continue;
                }
                if (ret == AVERROR_EXIT) {
                    return FeedResult::Aborted;
                }
                if (ret == AVERROR_EOF) {
                    m_flushPending = true;
                } else if (ret < 0) {
                    return FeedResult::Error;
                } else if (!m_packetPending) {
                    continue;
                }
            }

            if (budget && !budget->take()) return FeedResult::Yield;
            if (m_flushPending) {
                int ret;
                ret = avcodec_send_packet(m_dec, nullptr);
                if (ret == 0) {
                    m_flushPending = false;
                    m_draining = true;
                    return FeedResult::Sent;
                }
                if (ret == AVERROR(EAGAIN)) {
                    if (!budget) m_flushPending = false;
                    return FeedResult::NeedReceive;
                }
                if (ret == AVERROR_EOF) {
                    m_flushPending = false;
                    m_draining = true;
                    return FeedResult::Eof;
                }
                return FeedResult::Error;
            }

            int ret = avcodec_send_packet(m_dec, m_pkt);
            if (ret == AVERROR(EAGAIN)) {
                return FeedResult::NeedReceive;
            }
            m_packetPending = false;
            av_packet_unref(m_pkt);
            if (ret == AVERROR_EOF) {
                m_draining = true;
                return FeedResult::Eof;
            }
            if (ret < 0) {
                return FeedResult::Error;
            }
            return FeedResult::Sent;
        }
    }

    AVFormatContext *m_fmt;
    AVCodecContext *m_dec;
    int m_streamIndex;

    AVPacket *m_pkt = nullptr;
    AVFrame *m_frame = nullptr;
    bool m_packetPending = false;
    bool m_draining = false;
    bool m_needFeed = false;
    bool m_flushPending = false;
};
