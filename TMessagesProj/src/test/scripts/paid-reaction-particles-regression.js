const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/org/telegram/ui/Components/Reactions/ReactionsLayoutInBubble.java'), 'utf8');
const start = source.indexOf('        public boolean drawOverlay(Canvas canvas, float x, float y,');
const end = source.indexOf('\n        public void draw(Canvas canvas, float x, float y,', start);
assert(start >= 0 && end > start);
const overlay = source.slice(start, end);
assert(!source.includes('needsInvalidate = needsInvalidate ||'));
assert(overlay.includes('canvas.translate(x, y);'));
assert(overlay.includes('AndroidUtilities.rectTmp.set(0, 0, drawnWidth, height);'));
assert(overlay.includes('particles.draw(canvas, textColor, alpha)'));
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-reaction-particles-'));
const java = `import java.util.*;
public class ReactionParticles {
    static final int ANIMATION_TYPE_MOVE = 1;
    static int dp(int value) { return value; }
    static class RectF {
        float left, top, right, bottom;
        void set(float l, float t, float r, float b) { left=l; top=t; right=r; bottom=b; }
        void set(RectF b) { set(b.left,b.top,b.right,b.bottom); }
        void inset(float x,float y) { left+=x; right-=x; top+=y; bottom-=y; }
    }
    static class AndroidUtilities { static RectF rectTmp = new RectF(); }
    static class LiteMode {
        static final int FLAG_ANIMATED_EMOJI_REACTIONS = 1, FLAG_PARTICLES = 2;
        static boolean enabled = true;
        static boolean isEnabled(int flag) { return enabled; }
    }
    static class ColorUtils {
        static int blendARGB(int a, int b, float f) { return a; }
        static int setAlphaComponent(int color, int alpha) { return color; }
    }
    static class Path {
        enum Direction { CW }
        void rewind() {}
        void addRoundRect(RectF r, float x, float y, Direction d) {}
    }
    static class Canvas {
        float tx, ty;
        ArrayList<float[]> stack = new ArrayList<>();
        int save() { int n=stack.size(); stack.add(new float[]{tx,ty}); return n; }
        void translate(float x,float y) { tx+=x; ty+=y; }
        void scale(float x,float y,float cx,float cy) {}
        void clipPath(Path p) {}
        void restore() { float[] s=stack.remove(stack.size()-1);tx=s[0];ty=s[1]; }
        void restoreToCount(int n) { while(stack.size()>n) restore(); }
    }
    static class Particles {
        RectF bounds = new RectF();
        float px, py, drawnX, drawnY, lastAlpha;
        boolean initialized;
        int frames, draws;
        void setBounds(RectF r) { bounds.set(r); }
        boolean process() {
            frames++;
            if(!initialized) {px=(bounds.left+bounds.right)/2;py=(bounds.top+bounds.bottom)/2;initialized=true;}
            return true;
        }
        void draw(Canvas c,int color) { draw(c,color,1); }
        void draw(Canvas c,int color,float alpha) { drawnX=c.tx+px;drawnY=c.ty+py;lastAlpha=alpha;draws++; }
    }
    static class Bounce { float getScale(float strength) { return 1; } }
    Particles particles = new Particles();
    Bounce bounce = new Bounce();
    Path tagPath = new Path();
    int width=80,height=26,animationType,animateFromWidth=40,backgroundColor,textColor,serviceTextColor;
    boolean isSelected;
    float getDrawServiceShaderBackground() { return 0; }
    ${overlay}
    static void check(boolean v) { if(!v)throw new AssertionError(); }
    public static void main(String[] args) {
        ReactionParticles b = new ReactionParticles();
        Canvas canvas = new Canvas();
        int count=0;
        for(int frame=0;frame<=100;frame++) {
            float x=12+frame*2, y=600-frame*4;
            check(b.drawOverlay(canvas,x,y,1,0.75f,false));
            check(Math.abs(b.particles.drawnX-(x+40))<0.01f);
            check(Math.abs(b.particles.drawnY-(y+13))<0.01f);
            check(b.particles.lastAlpha==0.75f);
            check(canvas.tx==0 && canvas.ty==0 && canvas.stack.isEmpty());
            count++;
        }
        int calls=b.particles.draws, frames=b.particles.frames;
        check(!b.drawOverlay(canvas,0,0,1,0,false));
        check(calls==b.particles.draws && frames==b.particles.frames);
        b.isSelected=true;
        check(b.drawOverlay(canvas,10,20,1,0.25f,false));
        check(b.particles.draws==calls+2 && b.particles.lastAlpha==0.25f && canvas.stack.isEmpty());
        b.animationType=ANIMATION_TYPE_MOVE;
        b.drawOverlay(canvas,10,20,0.5f,1,false);
        check(b.particles.bounds.left==-4 && b.particles.bounds.right==64);
        LiteMode.enabled=false;
        check(!b.drawOverlay(canvas,10,20,1,1,false));
        System.out.println("PASS: " + count + " reaction moves keep existing particles attached; opacity, selection, width animation and Lite Mode");
    }
}`;
fs.writeFileSync(path.join(dir, 'ReactionParticles.java'), java);
cp.execFileSync('javac', ['ReactionParticles.java'], { cwd: dir, stdio: 'inherit' });
cp.execFileSync('java', ['ReactionParticles'], { cwd: dir, stdio: 'inherit' });
const oldCoordinates = java.replace('canvas.translate(x, y);', '').replace('AndroidUtilities.rectTmp.set(0, 0, drawnWidth, height);', 'AndroidUtilities.rectTmp.set(x, y, x + drawnWidth, y + height);');
fs.writeFileSync(path.join(dir, 'ReactionParticles.java'), oldCoordinates);
cp.execFileSync('javac', ['ReactionParticles.java'], { cwd: dir, stdio: 'inherit' });
const negative = cp.spawnSync('java', ['ReactionParticles'], { cwd: dir, encoding: 'utf8' });
assert.notEqual(negative.status, 0);
assert(negative.stderr.includes('AssertionError'));
console.log('PASS: previous absolute coordinates fail the same movement regression');
