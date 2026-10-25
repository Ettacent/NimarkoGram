/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons;

public final class AnimatedIconSpec {
    public final int[] icons;
    public final float[] frames;
    public final boolean pulse;
    public final float scale;
    public final String colorLayer;
    public final boolean alignedTransition;
    private final String laterColorLayer;
    private final float colorChangeFrame;
    private final float[] pulseFrames;
    public final int recordDelete;
    private final float[][] motionFrames;

    private AnimatedIconSpec(int[] icons, float[] frames, boolean pulse, float scale, String colorLayer) {
        this(icons, frames, pulse, scale, colorLayer, null, false);
    }

    private AnimatedIconSpec(int[] icons, float[] frames, boolean pulse, float scale, String colorLayer, float[] pulseFrames, boolean alignedTransition) {
        this(icons, frames, pulse, scale, colorLayer, pulseFrames, alignedTransition, null, 0);
    }

    private AnimatedIconSpec(int[] icons, float[] frames, boolean pulse, float scale, String colorLayer, float[] pulseFrames, boolean alignedTransition, String laterColorLayer, float colorChangeFrame) {
        this(icons, frames, pulse, scale, colorLayer, pulseFrames, alignedTransition, laterColorLayer, colorChangeFrame, 0, null);
    }

    private AnimatedIconSpec(int[] icons, float[] frames, boolean pulse, float scale, String colorLayer, float[] pulseFrames, boolean alignedTransition, String laterColorLayer, float colorChangeFrame, int recordDelete, float[][] motionFrames) {
        if (icons.length == 0 || icons.length != frames.length) throw new IllegalArgumentException();
        for (int i = 0; i < frames.length; i++) {
            if (icons[i] == 0 || Float.isNaN(frames[i]) || Float.isInfinite(frames[i])
                    || i > 0 && frames[i] <= frames[i - 1]) throw new IllegalArgumentException();
        }
        this.icons = icons.clone();
        this.frames = frames.clone();
        this.pulse = pulse;
        this.scale = scale;
        this.colorLayer = colorLayer;
        this.pulseFrames = pulseFrames == null ? null : pulseFrames.clone();
        this.alignedTransition = alignedTransition;
        this.laterColorLayer = laterColorLayer;
        this.colorChangeFrame = colorChangeFrame;
        this.recordDelete = recordDelete;
        this.motionFrames = motionFrames;
    }

    public static AnimatedIconSpec single(int icon) {
        return new AnimatedIconSpec(new int[]{icon}, new float[]{0}, false, 1f, null);
    }

    public static AnimatedIconSpec pulse(int icon) {
        return new AnimatedIconSpec(new int[]{icon}, new float[]{0}, true, 1f, null);
    }

    public static AnimatedIconSpec recordDelete(int fallbackIcon, int clip) {
        if (clip < 1 || clip > 3) throw new IllegalArgumentException();
        DeleteTracks.prepare();
        return new AnimatedIconSpec(new int[]{fallbackIcon}, new float[]{0}, false, 1f, null, null, false, null, 0, clip, null);
    }

    public static int slashed(int icon) {
        if (icon <= 0) throw new IllegalArgumentException();
        return -icon;
    }

    public static AnimatedIconSpec pair(int from, int to, float start, float end) {
        return frames(new int[]{from, to}, new float[]{start, end});
    }

    public static AnimatedIconSpec frames(int[] icons, float[] frames) {
        return new AnimatedIconSpec(icons, frames, false, 1f, null);
    }

    public AnimatedIconSpec withScale(float scale) {
        if (Float.isNaN(scale) || Float.isInfinite(scale) || scale <= 0 || scale > 1) throw new IllegalArgumentException();
        return new AnimatedIconSpec(icons, frames, pulse, scale, colorLayer, pulseFrames, alignedTransition, laterColorLayer, colorChangeFrame, recordDelete, motionFrames);
    }

    public AnimatedIconSpec withColorLayer(String layer) {
        return new AnimatedIconSpec(icons, frames, pulse, scale, layer, pulseFrames, alignedTransition, null, 0, recordDelete, motionFrames);
    }

    public AnimatedIconSpec withAlignedTransition() {
        return new AnimatedIconSpec(icons, frames, pulse, scale, colorLayer, pulseFrames, true, laterColorLayer, colorChangeFrame, recordDelete, motionFrames);
    }

    public AnimatedIconSpec withColorTransition(String first, String later, float frame) {
        if (first == null || later == null || first.equals(later) || Float.isNaN(frame) || Float.isInfinite(frame) || frame < 0) throw new IllegalArgumentException();
        return new AnimatedIconSpec(icons, frames, pulse, scale, first, pulseFrames, alignedTransition, later, frame, recordDelete, motionFrames);
    }

    public boolean acceptsLaterColorLayer(String layer) {
        return laterColorLayer != null && laterColorLayer.equals(layer);
    }

    public boolean usesLaterColor(float frame) {
        return laterColorLayer != null && frame >= colorChangeFrame;
    }

    public AnimatedIconSpec withPulseFrames(float... ranges) {
        if (ranges.length == 0 || (ranges.length & 1) != 0) throw new IllegalArgumentException();
        for (int i = 0; i < ranges.length; i++) {
            if (Float.isNaN(ranges[i]) || Float.isInfinite(ranges[i]) || ranges[i] < 0
                    || i > 0 && (i % 2 == 1 ? ranges[i] <= ranges[i - 1] : ranges[i] < ranges[i - 1])) {
                throw new IllegalArgumentException();
            }
        }
        return new AnimatedIconSpec(icons, frames, true, scale, colorLayer, ranges, alignedTransition, laterColorLayer, colorChangeFrame, recordDelete, motionFrames);
    }

    public AnimatedIconSpec withMotion(float[][] samples) {
        if (samples == null || samples.length == 0) throw new IllegalArgumentException();
        float[][] owned = new float[samples.length][];
        for (int i = 0; i < samples.length; i++) {
            float[] row = samples[i];
            if (row == null || row.length != 7) throw new IllegalArgumentException();
            for (float value : row) {
                if (Float.isNaN(value) || Float.isInfinite(value)) throw new IllegalArgumentException();
            }
            if (row[0] < 0 || i > 0 && row[0] <= samples[i - 1][0]
                    || row[3] < 0 || row[4] < 0 || row[6] < 0 || row[6] > 1) throw new IllegalArgumentException();
            owned[i] = row.clone();
        }
        return new AnimatedIconSpec(icons, frames, pulse, scale, colorLayer, pulseFrames, alignedTransition, laterColorLayer, colorChangeFrame, recordDelete, owned);
    }

    public boolean hasMotion() {
        return motionFrames != null;
    }

    public void sampleMotion(float frame, float[] out) {
        if (out.length < 6) throw new IllegalArgumentException();
        if (motionFrames == null) {
            out[0] = out[1] = out[4] = 0;
            out[2] = out[3] = out[5] = 1;
            return;
        }
        int low = 0, high = motionFrames.length - 1;
        if (Float.isNaN(frame) || frame <= motionFrames[0][0]) {
            high = 0;
        } else if (frame >= motionFrames[high][0]) {
            low = high;
        } else {
            while (high - low > 1) {
                int middle = (low + high) >>> 1;
                if (frame < motionFrames[middle][0]) high = middle; else low = middle;
            }
        }
        float[] from = motionFrames[low], to = motionFrames[high];
        float progress = low == high ? 0 : (frame - from[0]) / (to[0] - from[0]);
        for (int i = 0; i < 6; i++) out[i] = from[i + 1] + (to[i + 1] - from[i + 1]) * progress;
    }

    public float pulsePhase(float frame, int totalFrames) {
        if (!pulse) return 0;
        if (pulseFrames == null) return Math.max(0, Math.min(1, frame / Math.max(1, totalFrames - 1f)));
        for (int i = 0; i < pulseFrames.length; i += 2) {
            if (frame >= pulseFrames[i] && frame <= pulseFrames[i + 1]) {
                return (frame - pulseFrames[i]) / (pulseFrames[i + 1] - pulseFrames[i]);
            }
        }
        return 0;
    }

    public boolean acceptsColorLayer(String layer) {
        return colorLayer != null && (colorLayer.equals("**") || colorLayer.equals(layer));
    }

    public int segment(float frame) {
        int i = 0;
        while (i + 1 < frames.length && frame >= frames[i + 1]) i++;
        return i;
    }

    public float progress(int segment, float frame) {
        if (segment + 1 >= frames.length) return 0;
        float t = Math.max(0, Math.min(1, (frame - frames[segment]) / (frames[segment + 1] - frames[segment])));
        return t * t * (3 - 2 * t);
    }

    public static final class DeletePose {
        public float bodyX, bodyBottom, bodyWidth, bodyHeight, bodyAlpha;
        public float lidY, lidRotation, lidScaleX, lidScaleY, lidAlpha, dotAlpha;
        public float shapeAlpha, shapeY, frame;
    }

    public void sampleRecordDelete(float frame, DeletePose out) {
        if (recordDelete == 0) throw new IllegalStateException();
        float f = Float.isNaN(frame) ? 0 : Math.max(0, Math.min(recordDelete == 3 ? 40 : 52, frame));
        out.frame = f;
        boolean preview = recordDelete == 2, live = recordDelete == 3;
        out.lidY = live ? DeleteTracks.LIVE_LID.at(f) : DeleteTracks.LID.at(preview ? f - 4 : f);
        out.lidRotation = live ? DeleteTracks.LIVE_ROTATION.at(f) : DeleteTracks.ROTATION.at(preview ? f - 4 : f);
        float grow = live ? DeleteTracks.LID_GROW.at(f) : 1;
        out.lidScaleX = live ? .84f + .16f * grow : 1;
        out.lidScaleY = live ? .31f + .69f * grow : 1;
        out.lidAlpha = preview || f >= 10 ? 1 : 0;
        out.dotAlpha = 0;
        out.shapeAlpha = 0;
        out.shapeY = 72;
        out.bodyAlpha = 1;
        out.bodyX = 50.2315f;
        out.bodyBottom = 92.057f;
        if (preview && f < 14) {
            out.bodyX = 50;
            out.bodyBottom = 92.3f;
            out.bodyWidth = 60.6f;
            out.bodyHeight = 60.6f - 44.5f * Math.max(0, (f - 9) / 5);
        } else if (preview) {
            float t = DeleteTracks.RED_GROW.at(f - 4);
            out.bodyWidth = 60.534f + .045f * t;
            out.bodyHeight = 17.742f + 42.25f * t;
        } else {
            out.bodyWidth = (live ? DeleteTracks.LIVE_WIDTH : DeleteTracks.WIDTH).at(f);
            out.bodyHeight = (live ? DeleteTracks.LIVE_HEIGHT : DeleteTracks.HEIGHT).at(f);
            if (f < 10) {
                float morph = Math.max(0, Math.min(1, (out.bodyWidth - 40) / 20.534f));
                out.bodyX = 50.25f;
                out.bodyBottom = DeleteTracks.DROP.at(f) + 20 + .057f * morph;
            }
            float appearance = Math.max(0, Math.min(1, (f - 8) / 6));
            out.bodyAlpha = appearance * appearance * (3 - 2 * appearance);
            out.shapeAlpha = 1 - out.bodyAlpha;
            out.shapeY = DeleteTracks.DROP.at(f);
        }
    }

    private static final class DeleteTracks {
        static void prepare() { }
        static final Track LID = new Track(true, new float[][]{
                {5,21,.333f,0,.833f,1,7.333f,.833f}, {10,65,.228f,0,.445f,1,-.833f,7.333f},
                {21,16,.167f,0,.833f,.833f,-7.333f,-.333f}, {25,21,.167f,.167f,.833f,.833f,.333f,0},
                {29,18,.167f,.167f,.833f,.833f,0,-.5f}, {33,21,.167f,.167f,.833f,.833f,0,0}, {40,21}});
        static final Track LIVE_LID = new Track(true, new float[][]{
                {10,77.5f,.413f,0,.714f,.689f,-.334f,8.637f}, {14,49.119f,.271f,.429f,.519f,1,-12.928f,4.396f},
                {21,15,.29f,0,.71f,1,.136f,-.333f}, {25,23.5f,.29f,0,.71f,1,.333f,0},
                {29,18.5f,.29f,0,.71f,1,0,-.5f}, {33,22.25f,.29f,0,.4f,1,0,0}, {40,21}});
        static final Track ROTATION = new Track(false, new float[][]{
                {29,0,0,0,1,1}, {33,5,0,0,1,1}, {37,-5,0,0,1,1}, {43,0}});
        static final Track LIVE_ROTATION = new Track(false, new float[][]{
                {14,0,.5f,0,.71f,1}, {19,3,.29f,0,.71f,1}, {23,-5,.29f,0,.71f,1},
                {27,3,.29f,0,.71f,1}, {31,-4,.29f,0,.4f,1}, {38,0}});
        static final Track LID_GROW = new Track(false, new float[][]{{10,0,.6f,0,.833f,.833f},{14,1}});
        static final Track DROP = new Track(true, new float[][]{{1,51.938f,.3f,0,.7f,1,3.344f,-3.344f},{10,72}});
        static final Track RED_GROW = new Track(false, new float[][]{{10,0,.3f,0,.3f,1},{21,1}});
        static final Track WIDTH = new Track(false, new float[][]{
                {1,40,.333f,0,.4f,1},{6,49.312f,.6f,0,.7f,1},{10,60.534f,.3f,0,.3f,1},{21,60.579f}});
        static final Track HEIGHT = new Track(false, new float[][]{
                {1,40,.333f,0,.4f,1},{6,28.562f,.6f,0,.7f,1},{10,17.742f,.3f,0,.3f,1},{21,59.992f}});
        static final Track LIVE_WIDTH = new Track(false, new float[][]{
                {1,40,.8f,0,.7f,1},{10,60.534f,.3f,0,.6f,1},{20,60.544f,.3f,0,.4f,1},{25,60.579f}});
        static final Track LIVE_HEIGHT = new Track(false, new float[][]{
                {1,40,.8f,0,.7f,1},{10,17.742f,.3f,0,.6f,1},{20,63.992f,.3f,0,.4f,1},{25,59.992f}});
    }

    private static final class Track {
        private static final int SAMPLES = 128;
        private final float[][] keys, lengths;

        Track(boolean spatial, float[][] keys) {
            this.keys = keys;
            lengths = spatial ? new float[keys.length - 1][SAMPLES + 1] : null;
            if (spatial) for (int k = 0; k < lengths.length; k++) {
                float previous = keys[k][1];
                for (int j = 1; j <= SAMPLES; j++) {
                    float value = spatial(k, j / (float) SAMPLES);
                    lengths[k][j] = lengths[k][j - 1] + Math.abs(value - previous);
                    previous = value;
                }
            }
        }

        float at(float frame) {
            if (frame <= keys[0][0]) return keys[0][1];
            int k = 0;
            while (k + 1 < keys.length && frame >= keys[k + 1][0]) k++;
            if (k == keys.length - 1) return keys[k][1];
            float[] key = keys[k];
            float progress = (frame - key[0]) / (keys[k + 1][0] - key[0]);
            float low = 0, high = 1;
            for (int i = 0; i < 18; i++) {
                float mid = (low + high) * .5f;
                if (cubic(0, key[2], key[4], 1, mid) < progress) low = mid; else high = mid;
            }
            float eased = cubic(0, key[3], key[5], 1, (low + high) * .5f);
            if (lengths == null) return key[1] + (keys[k + 1][1] - key[1]) * eased;
            float[] arc = lengths[k];
            if (arc[SAMPLES] == 0) return key[1];
            float distance = eased * arc[SAMPLES];
            int a = 0, b = SAMPLES;
            while (b - a > 1) { int mid = (a + b) / 2; if (arc[mid] < distance) a = mid; else b = mid; }
            float t = arc[b] == arc[a] ? 0 : (distance - arc[a]) / (arc[b] - arc[a]);
            return spatial(k, (a + t) / SAMPLES);
        }

        private float spatial(int k, float t) {
            float[] key = keys[k];
            float end = keys[k + 1][1];
            return cubic(key[1], key[1] + key[6], end + key[7], end, t);
        }

        private static float cubic(float a, float b, float c, float d, float t) {
            float u = 1 - t;
            return u*u*u*a + 3*u*u*t*b + 3*u*t*t*c + t*t*t*d;
        }
    }
}
