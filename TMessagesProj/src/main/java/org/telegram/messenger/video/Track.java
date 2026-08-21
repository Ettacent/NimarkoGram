/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger.video;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import com.coremedia.iso.boxes.AbstractMediaHeaderBox;
import com.coremedia.iso.boxes.SampleDescriptionBox;
import com.coremedia.iso.boxes.SoundMediaHeaderBox;
import com.coremedia.iso.boxes.VideoMediaHeaderBox;
import com.mp4parser.iso14496.part15.AvcConfigurationBox;
import com.coremedia.iso.boxes.sampleentry.AudioSampleEntry;
import com.coremedia.iso.boxes.sampleentry.VisualSampleEntry;
import com.googlecode.mp4parser.boxes.mp4.ESDescriptorBox;
import com.googlecode.mp4parser.boxes.mp4.objectdescriptors.AudioSpecificConfig;
import com.googlecode.mp4parser.boxes.mp4.objectdescriptors.DecoderConfigDescriptor;
import com.googlecode.mp4parser.boxes.mp4.objectdescriptors.ESDescriptor;
import com.googlecode.mp4parser.boxes.mp4.objectdescriptors.SLConfigDescriptor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

public class Track {

    private static class SamplePresentationTime {

        private int index;
        private long presentationTime;
        private long dt;

        public SamplePresentationTime(int idx, long time) {
            index = idx;
            presentationTime = time;
        }
    }

    private long trackId;
    private ArrayList<Sample> samples = new ArrayList<>();
    private long duration = 0;
    private int[] sampleCompositions;
    private String handler;
    private AbstractMediaHeaderBox headerBox;
    private SampleDescriptionBox sampleDescriptionBox;
    private LinkedList<Integer> syncSamples = null;
    private int timeScale;
    private Date creationTime = new Date();
    private int height;
    private int width;
    private float volume = 0;
    private long[] sampleDurations;
    private ArrayList<SamplePresentationTime> samplePresentationTimes = new ArrayList<>();
    private boolean isAudio;
    private static Map<Integer, Integer> samplingFrequencyIndexMap = new HashMap<>();
    private boolean first = true;

    static {
        samplingFrequencyIndexMap.put(96000, 0x0);
        samplingFrequencyIndexMap.put(88200, 0x1);
        samplingFrequencyIndexMap.put(64000, 0x2);
        samplingFrequencyIndexMap.put(48000, 0x3);
        samplingFrequencyIndexMap.put(44100, 0x4);
        samplingFrequencyIndexMap.put(32000, 0x5);
        samplingFrequencyIndexMap.put(24000, 0x6);
        samplingFrequencyIndexMap.put(22050, 0x7);
        samplingFrequencyIndexMap.put(16000, 0x8);
        samplingFrequencyIndexMap.put(12000, 0x9);
        samplingFrequencyIndexMap.put(11025, 0xa);
        samplingFrequencyIndexMap.put(8000, 0xb);
    }

    private static int findAnnexBStartCode(byte[] data, int from) {
        for (int i = Math.max(0, from); i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0
                    && (data[i + 2] == 1
                    || i + 3 < data.length && data[i + 2] == 0 && data[i + 3] == 1)) {
                return i;
            }
        }
        return -1;
    }

    private static int annexBStartCodeLength(byte[] data, int offset) {
        return offset + 3 < data.length && data[offset + 2] == 0 ? 4 : 3;
    }

    private static void addAvcParameterSet(byte[] data, int offset, int limit,
                                           ArrayList<byte[]> sps, ArrayList<byte[]> pps) {
        while (limit > offset && data[limit - 1] == 0) {
            limit--;
        }
        if (offset >= limit) {
            return;
        }
        int type = data[offset] & 0x1f;
        ArrayList<byte[]> destination = type == 7 ? sps : type == 8 ? pps : null;
        if (destination == null) {
            return;
        }
        byte[] parameterSet = Arrays.copyOfRange(data, offset, limit);
        for (byte[] existing : destination) {
            if (Arrays.equals(existing, parameterSet)) {
                return;
            }
        }
        destination.add(parameterSet);
    }

    private static boolean collectAvcConfigurationRecord(byte[] data,
                                                         ArrayList<byte[]> sps,
                                                         ArrayList<byte[]> pps) {
        if (data.length < 7 || data[0] != 1) {
            return false;
        }
        int offset = 5;
        int spsCount = data[offset++] & 0x1f;
        for (int i = 0; i < spsCount; i++) {
            if (offset + 2 > data.length) {
                return false;
            }
            int length = ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
            offset += 2;
            if (length <= 0 || offset + length > data.length) {
                return false;
            }
            addAvcParameterSet(data, offset, offset + length, sps, pps);
            offset += length;
        }
        if (offset >= data.length) {
            return false;
        }
        int ppsCount = data[offset++] & 0xff;
        for (int i = 0; i < ppsCount; i++) {
            if (offset + 2 > data.length) {
                return false;
            }
            int length = ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
            offset += 2;
            if (length <= 0 || offset + length > data.length) {
                return false;
            }
            addAvcParameterSet(data, offset, offset + length, sps, pps);
            offset += length;
        }
        return !sps.isEmpty() || !pps.isEmpty();
    }

    private static boolean collectLengthPrefixedAvcParameterSets(byte[] data,
                                                                 ArrayList<byte[]> sps,
                                                                 ArrayList<byte[]> pps) {
        int offset = 0;
        boolean found = false;
        while (offset + 4 <= data.length) {
            int length = ((data[offset] & 0xff) << 24)
                    | ((data[offset + 1] & 0xff) << 16)
                    | ((data[offset + 2] & 0xff) << 8)
                    | (data[offset + 3] & 0xff);
            offset += 4;
            if (length <= 0 || length > data.length - offset) {
                return false;
            }
            int oldSpsCount = sps.size();
            int oldPpsCount = pps.size();
            addAvcParameterSet(data, offset, offset + length, sps, pps);
            found |= oldSpsCount != sps.size() || oldPpsCount != pps.size();
            offset += length;
        }
        return found && offset == data.length;
    }

    private static void collectAvcParameterSets(ByteBuffer source,
                                                ArrayList<byte[]> sps,
                                                ArrayList<byte[]> pps) {
        if (source == null) {
            return;
        }
        ByteBuffer buffer = source.duplicate();
        buffer.position(0);
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        int originalSpsCount = sps.size();
        int originalPpsCount = pps.size();
        if (collectAvcConfigurationRecord(data, sps, pps)) {
            return;
        }
        while (sps.size() > originalSpsCount) {
            sps.remove(sps.size() - 1);
        }
        while (pps.size() > originalPpsCount) {
            pps.remove(pps.size() - 1);
        }
        if (collectLengthPrefixedAvcParameterSets(data, sps, pps)) {
            return;
        }
        while (sps.size() > originalSpsCount) {
            sps.remove(sps.size() - 1);
        }
        while (pps.size() > originalPpsCount) {
            pps.remove(pps.size() - 1);
        }

        int start = findAnnexBStartCode(data, 0);
        if (start < 0) {
            addAvcParameterSet(data, 0, data.length, sps, pps);
            return;
        }
        while (start >= 0) {
            int nalStart = start + annexBStartCodeLength(data, start);
            int next = findAnnexBStartCode(data, nalStart);
            addAvcParameterSet(data, nalStart, next >= 0 ? next : data.length, sps, pps);
            start = next;
        }
    }

    private static int avcLevelIndication(int level) {
        if (level == MediaCodecInfo.CodecProfileLevel.AVCLevel1) return 10;
        if (level == MediaCodecInfo.CodecProfileLevel.AVCLevel1b) return 11;
        if (level == MediaCodecInfo.CodecProfileLevel.AVCLevel11) return 11;
        if (level == MediaCodecInfo.CodecProfileLevel.AVCLevel12) return 12;
        if (level == MediaCodecInfo.CodecProfileLevel.AVCLevel13) return 13;
        if (level == MediaCodecInfo.CodecProfileLevel.AVCLevel2) return 20;
        if (level == MediaCodecInfo.CodecProfileLevel.AVCLevel21) return 21;
        if (level == MediaCodecInfo.CodecProfileLevel.AVCLevel22) return 22;
        if (level == MediaCodecInfo.CodecProfileLevel.AVCLevel3) return 30;
        if (level == MediaCodecInfo.CodecProfileLevel.AVCLevel31) return 31;
        if (level == MediaCodecInfo.CodecProfileLevel.AVCLevel32) return 32;
        if (level == MediaCodecInfo.CodecProfileLevel.AVCLevel4) return 40;
        if (level == MediaCodecInfo.CodecProfileLevel.AVCLevel41) return 41;
        if (level == MediaCodecInfo.CodecProfileLevel.AVCLevel42) return 42;
        if (level == MediaCodecInfo.CodecProfileLevel.AVCLevel5) return 50;
        if (level == MediaCodecInfo.CodecProfileLevel.AVCLevel51) return 51;
        if (level == MediaCodecInfo.CodecProfileLevel.AVCLevel52) return 52;
        return 0;
    }

    private static int avcProfileIndication(int profile) {
        if (profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline) return 66;
        if (profile == MediaCodecInfo.CodecProfileLevel.AVCProfileMain) return 77;
        if (profile == MediaCodecInfo.CodecProfileLevel.AVCProfileExtended) return 88;
        if (profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh) return 100;
        if (profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10) return 110;
        if (profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422) return 122;
        if (profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444) return 244;
        return 0;
    }

    public Track(int id, MediaFormat format, boolean audio) {
        trackId = id;
        isAudio = audio;
        if (!isAudio) {
            width = format.getInteger(MediaFormat.KEY_WIDTH);
            height = format.getInteger(MediaFormat.KEY_HEIGHT);
            timeScale = 90000;
            syncSamples = new LinkedList<>();
            handler = "vide";
            headerBox = new VideoMediaHeaderBox();
            sampleDescriptionBox = new SampleDescriptionBox();
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.equals("video/avc")) {
                VisualSampleEntry visualSampleEntry = new VisualSampleEntry("avc1");
                visualSampleEntry.setDataReferenceIndex(1);
                visualSampleEntry.setDepth(24);
                visualSampleEntry.setFrameCount(1);
                visualSampleEntry.setHorizresolution(72);
                visualSampleEntry.setVertresolution(72);
                visualSampleEntry.setWidth(width);
                visualSampleEntry.setHeight(height);

                AvcConfigurationBox avcConfigurationBox = new AvcConfigurationBox();

                ArrayList<byte[]> spsArray = new ArrayList<>();
                ArrayList<byte[]> ppsArray = new ArrayList<>();
                collectAvcParameterSets(format.getByteBuffer("csd-0"), spsArray, ppsArray);
                collectAvcParameterSets(format.getByteBuffer("csd-1"), spsArray, ppsArray);
                if (!spsArray.isEmpty()) {
                    avcConfigurationBox.setSequenceParameterSets(spsArray);
                }
                if (!ppsArray.isEmpty()) {
                    avcConfigurationBox.setPictureParameterSets(ppsArray);
                }

                byte[] sps = spsArray.isEmpty() ? null : spsArray.get(0);
                if (sps != null && sps.length >= 4) {
                    avcConfigurationBox.setAvcProfileIndication(sps[1] & 0xff);
                    avcConfigurationBox.setProfileCompatibility(sps[2] & 0xff);
                    avcConfigurationBox.setAvcLevelIndication(sps[3] & 0xff);
                } else {
                    int profile = format.containsKey(MediaFormat.KEY_PROFILE)
                            ? avcProfileIndication(format.getInteger(MediaFormat.KEY_PROFILE)) : 0;
                    int level = format.containsKey("level")
                            ? avcLevelIndication(format.getInteger("level")) : 0;
                    avcConfigurationBox.setAvcProfileIndication(profile != 0 ? profile : 66);
                    avcConfigurationBox.setAvcLevelIndication(level != 0 ? level : 31);
                    avcConfigurationBox.setProfileCompatibility(0);
                }
                avcConfigurationBox.setBitDepthLumaMinus8(-1);
                avcConfigurationBox.setBitDepthChromaMinus8(-1);
                avcConfigurationBox.setChromaFormat(-1);
                avcConfigurationBox.setConfigurationVersion(1);
                avcConfigurationBox.setLengthSizeMinusOne(3);

                visualSampleEntry.addBox(avcConfigurationBox);
                sampleDescriptionBox.addBox(visualSampleEntry);
            } else if (mime.equals("video/mp4v")) {
                VisualSampleEntry visualSampleEntry = new VisualSampleEntry("mp4v");
                visualSampleEntry.setDataReferenceIndex(1);
                visualSampleEntry.setDepth(24);
                visualSampleEntry.setFrameCount(1);
                visualSampleEntry.setHorizresolution(72);
                visualSampleEntry.setVertresolution(72);
                visualSampleEntry.setWidth(width);
                visualSampleEntry.setHeight(height);

                sampleDescriptionBox.addBox(visualSampleEntry);
            } else if (mime.equals("video/hevc")) {
                if (format.getByteBuffer("csd-0") != null) {
                    ByteBuffer byteBuffer = format.getByteBuffer("csd-0");
                    byte bytes[] = byteBuffer.array();
                    int vpsPosition = -1;
                    int spsPosition = -1;
                    int ppsPosition = -1;
                    int countBufferInititation = 0;
                    for (int i = 0; i < bytes.length; i++) {
                        if (countBufferInititation == 3 && bytes[i] == 1) {
                            if (vpsPosition == -1) {
                                vpsPosition = i - 3;
                            } else if (spsPosition == -1) {
                                spsPosition = i - 3;
                            } else if (ppsPosition == -1) {
                                ppsPosition = i - 3;
                            }
                        }
                        if (bytes[i] == 0) {
                            countBufferInititation++;
                        } else {
                            countBufferInititation = 0;
                        }
                    }
                    byte[] vps = new byte[spsPosition - 4];
                    byte[] sps = new byte[ppsPosition - spsPosition - 4];
                    byte[] pps = new byte[bytes.length - ppsPosition - 4];
                    for (int i = 0; i < bytes.length; i++) {
                        if (i < spsPosition) {
                            if (i - 4 >= 0) {
                                vps[i - 4] = bytes[i];
                            }
                        } else if (i < ppsPosition) {
                            if (i - spsPosition - 4 >= 0) {
                                sps[i - spsPosition - 4] = bytes[i];
                            }
                        } else {
                            if (i - ppsPosition - 4 >= 0) {
                                pps[i - ppsPosition - 4] = bytes[i];
                            }
                        }
                    }

                    try {
                        VisualSampleEntry visualSampleEntry = HevcDecoderConfigurationRecord.parseFromCsd(Arrays.asList(ByteBuffer.wrap(vps),ByteBuffer.wrap(pps), ByteBuffer.wrap(sps)));
                        visualSampleEntry.setWidth(width);
                        visualSampleEntry.setHeight(height);
                        sampleDescriptionBox.addBox(visualSampleEntry);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        } else {
            volume = 1;
            timeScale = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            handler = "soun";
            headerBox = new SoundMediaHeaderBox();
            sampleDescriptionBox = new SampleDescriptionBox();
            AudioSampleEntry audioSampleEntry = new AudioSampleEntry("mp4a");
            audioSampleEntry.setChannelCount(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
            audioSampleEntry.setSampleRate(format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
            audioSampleEntry.setDataReferenceIndex(1);
            audioSampleEntry.setSampleSize(16);

            ESDescriptorBox esds = new ESDescriptorBox();
            ESDescriptor descriptor = new ESDescriptor();
            descriptor.setEsId(0);

            SLConfigDescriptor slConfigDescriptor = new SLConfigDescriptor();
            slConfigDescriptor.setPredefined(2);
            descriptor.setSlConfigDescriptor(slConfigDescriptor);

            String mime;
            if (format.containsKey("mime")) {
                mime = format.getString("mime");
            } else {
                mime = "audio/mp4-latm";
            }

            DecoderConfigDescriptor decoderConfigDescriptor = new DecoderConfigDescriptor();
            if ("audio/mpeg".equals(mime)) {
                decoderConfigDescriptor.setObjectTypeIndication(0x69);
            } else {
                decoderConfigDescriptor.setObjectTypeIndication(0x40);
            }
            decoderConfigDescriptor.setStreamType(5);
            decoderConfigDescriptor.setBufferSizeDB(1536);
            if (format.containsKey("max-bitrate")) {
                decoderConfigDescriptor.setMaxBitRate(format.getInteger("max-bitrate"));
            } else {
                decoderConfigDescriptor.setMaxBitRate(96000);
            }
            decoderConfigDescriptor.setAvgBitRate(timeScale);

            AudioSpecificConfig audioSpecificConfig = new AudioSpecificConfig();
            audioSpecificConfig.setAudioObjectType(2);
            audioSpecificConfig.setSamplingFrequencyIndex(samplingFrequencyIndexMap.get((int) audioSampleEntry.getSampleRate()));
            audioSpecificConfig.setChannelConfiguration(audioSampleEntry.getChannelCount());
            decoderConfigDescriptor.setAudioSpecificInfo(audioSpecificConfig);

            descriptor.setDecoderConfigDescriptor(decoderConfigDescriptor);

            ByteBuffer data = descriptor.serialize();
            esds.setData(data);
            audioSampleEntry.addBox(esds);
            sampleDescriptionBox.addBox(audioSampleEntry);
        }
    }

    public long getTrackId() {
        return trackId;
    }

    public void addSample(long offset, MediaCodec.BufferInfo bufferInfo) {
        boolean isSyncFrame = !isAudio && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0;
        samples.add(new Sample(offset, bufferInfo.size));
        if (syncSamples != null && isSyncFrame) {
            syncSamples.add(samples.size());
        }
        samplePresentationTimes.add(new SamplePresentationTime(samplePresentationTimes.size(), (bufferInfo.presentationTimeUs * timeScale + 500000L) / 1000000L));
    }

    public void prepare() {
        duration = 0;

        ArrayList<SamplePresentationTime> original = new ArrayList<>(samplePresentationTimes);
        Collections.sort(samplePresentationTimes, (o1, o2) -> {
            if (o1.presentationTime > o2.presentationTime) {
                return 1;
            } else if (o1.presentationTime < o2.presentationTime) {
                return -1;
            }
            return 0;
        });
        long lastPresentationTimeUs = 0;
        sampleDurations = new long[samplePresentationTimes.size()];
        long minDelta = Long.MAX_VALUE;
        boolean outOfOrder = false;
        for (int a = 0; a < samplePresentationTimes.size(); a++) {
            SamplePresentationTime presentationTime = samplePresentationTimes.get(a);
            long delta = presentationTime.presentationTime - lastPresentationTimeUs;
            lastPresentationTimeUs = presentationTime.presentationTime;
            sampleDurations[presentationTime.index] = delta;
            if (presentationTime.index != 0) {
                duration += delta;
            }
            if (delta > 0 && delta < Integer.MAX_VALUE) {
                minDelta = Math.min(minDelta, delta);
            }
            if (presentationTime.index != a) {
                outOfOrder = true;
            }
        }
        if (sampleDurations.length > 0) {
            sampleDurations[0] = minDelta;
            duration += minDelta;
        }
        for (int a = 1; a < original.size(); a++) {
            original.get(a).dt = sampleDurations[a] + original.get(a - 1).dt;
        }
        if (outOfOrder) {
            sampleCompositions = new int[samplePresentationTimes.size()];
            for (int a = 0; a < samplePresentationTimes.size(); a++) {
                SamplePresentationTime presentationTime = samplePresentationTimes.get(a);
                sampleCompositions[presentationTime.index] = (int) (presentationTime.presentationTime - presentationTime.dt);
            }
        }
    }

    public ArrayList<Sample> getSamples() {
        return samples;
    }

    public long getLastFrameTimestamp() {
        return ((duration - sampleDurations[sampleDurations.length - 1]) * 1000000 - 500000) / timeScale;
    }

    public long getDuration() {
        return duration;
    }

    public String getHandler() {
        return handler;
    }

    public AbstractMediaHeaderBox getMediaHeaderBox() {
        return headerBox;
    }

    public int[] getSampleCompositions() {
        return sampleCompositions;
    }

    public SampleDescriptionBox getSampleDescriptionBox() {
        return sampleDescriptionBox;
    }

    public long[] getSyncSamples() {
        if (syncSamples == null || syncSamples.isEmpty()) {
            return null;
        }
        long[] returns = new long[syncSamples.size()];
        for (int i = 0; i < syncSamples.size(); i++) {
            returns[i] = syncSamples.get(i);
        }
        return returns;
    }

    public int getTimeScale() {
        return timeScale;
    }

    public Date getCreationTime() {
        return creationTime;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public float getVolume() {
        return volume;
    }

    public long[] getSampleDurations() {
        return sampleDurations;
    }

    public boolean isAudio() {
        return isAudio;
    }
}
