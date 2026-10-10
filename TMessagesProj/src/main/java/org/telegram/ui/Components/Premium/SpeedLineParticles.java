/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components.Premium;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

import app.nimarkogram.messenger.utils.NimarkoUiAnimationClock;
public class SpeedLineParticles {

    public static class Drawable {

        public RectF rect = new RectF();
        public RectF screenRect = new RectF();
        public boolean paused;
        private Paint paint = new Paint();
        private float lines[];

        ArrayList<Drawable.Particle> particles = new ArrayList<>();
        public float speedScale = 1f;

        public final int count;
        public boolean useGradient;
        public int size1 = 14, size2 = 12, size3 = 10;
        public long minLifeTime = 2000;
        private int lastColor;
        private float dt;
        private long animationTime;
        private long prevTime = -1;
        private int clockEpoch = -1;
        private boolean wasPaused;
        private boolean resetPending;

        public Drawable(int count) {
            this.count = count;
            lines = new float[count * 4];
        }

        public void init() {
            if (particles.isEmpty()) {
                for (int i = 0; i < count; i++) {
                    particles.add(new Drawable.Particle());
                }
            }
            updateColors();
        }

        public void updateColors() {
            int c = ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_premiumStartSmallStarsColor2), 80);
            if (lastColor != c) {
                lastColor = c;
                paint.setColor(lastColor);
            }
        }



        public void resetPositions() {
            if (paused || NimarkoUiAnimationClock.isPaused()) {
                resetPending = true;
                return;
            }
            long time = sampleTime();
            for (int i = 0; i < particles.size(); i++) {
                particles.get(i).genPosition(time, true);
            }
            resetPending = false;
        }

        private long sampleTime() {
            long now = NimarkoUiAnimationClock.now();
            int epoch = NimarkoUiAnimationClock.epoch();
            boolean stopped = paused || NimarkoUiAnimationClock.isPaused();
            long diff = prevTime < 0 || epoch != clockEpoch || stopped || wasPaused ? 0 : Math.max(0, now - prevTime);
            animationTime += diff;
            dt = Math.min(diff, 50);
            prevTime = now;
            clockEpoch = epoch;
            wasPaused = stopped;
            return animationTime;
        }

        public void onDraw(Canvas canvas) {
            long time = sampleTime();
            if (resetPending && !wasPaused) {
                resetPositions();
            }
            for (int i = 0; i < particles.size(); i++) {
                Drawable.Particle particle = particles.get(i);
                if (!wasPaused && (time > particle.lifeTime || !screenRect.contains(particle.x, particle.y))) {
                    particle.genPosition(time, false);
                }
                particle.draw(canvas, i, time);
            }
            canvas.drawLines(lines, paint);
        }

        long pausedTime;

        private class Particle {
            private float x, y;
            private float vecX, vecY;
            private int starIndex;
            private long lifeTime;
            private int alpha;
            float inProgress;

            public void draw(Canvas canvas, int index,  long time) {
                if (dt > 0) {
                    float speed = AndroidUtilities.dp(4) * (dt / 660f) * speedScale;
                    x += vecX * speed;
                    y += vecY * speed;

                    if (inProgress != 1f) {
                        inProgress += dt / 200;
                        if (inProgress > 1f) {
                            inProgress = 1f;
                        }
                    }
                }
                lines[4 * index ] = x;
                lines[4 * index + 1] = y;
                lines[4 * index + 2] = x + AndroidUtilities.dp(30) * vecX;
                lines[4 * index + 3] = y + AndroidUtilities.dp(30) * vecY;
            }

            public void genPosition(long time, boolean reset) {
                lifeTime = time + minLifeTime + Utilities.fastRandom.nextInt(1000);
                RectF currentRect = reset ? screenRect : rect;
                float randX = currentRect.left + Math.abs(Utilities.fastRandom.nextInt() % currentRect.width());
                float randY = currentRect.top + Math.abs(Utilities.fastRandom.nextInt() % currentRect.height());
                x = randX;
                y = randY;
                double a = Math.atan2(x - rect.centerX(), y - rect.centerY());
                vecX = (float) Math.sin(a);
                vecY = (float) Math.cos(a);
                alpha = (int) (255 * ((50 + Utilities.fastRandom.nextInt(50)) / 100f));

                inProgress = 0;
            }
        }
    }
}
