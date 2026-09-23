"""UI-side live LG contracts; renderer/registry behavior belongs to blur3 tests.

Execute production source setup and the picker fade override with small host
stubs. No Android fragment recreation, GPU execution, or application build.
"""
import pathlib
import re
import shutil
import subprocess
import tempfile
import unittest


UI = pathlib.Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui"


def method(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


def run_java(source):
    with tempfile.TemporaryDirectory(prefix="lg-ui-toggle-") as temp:
        path = pathlib.Path(temp) / "ToggleHarness.java"
        path.write_text(source)
        subprocess.run(["javac", str(path)], check=True, capture_output=True, text=True)
        subprocess.run(["java", "-cp", temp, "ToggleHarness"],
                       check=True, capture_output=True, text=True)


class LiveToggleCallsiteTests(unittest.TestCase):
    def test_home_frosted_source_hash_changes_with_liquid_material(self):
        source = (UI / "DialogsActivity.java").read_text()
        setup = source[source.index("iBlur3SourceGlassFrosted.setupRenderer("):]
        calculate = method(setup, "public void renderNodeCalculateHash(")
        self.assertIn("hash.add(BlurredBackgroundDrawableViewFactory.isLiquidGlassEnabled());", calculate)

    def test_ui_factory_capability_never_snapshots_lite_mode(self):
        for path in UI.rglob("*.java"):
            if "blur3" in path.parts:
                continue  # Shared agent owns the effective runtime gate.
            source = re.sub(r"/\*.*?\*/|//[^\n]*", "", path.read_text(), flags=re.S)
            with self.subTest(path=path.relative_to(UI)):
                self.assertNotRegex(source, r"setLiquidGlassEffectAllowed\(\s*LiteMode\.")

    def test_unused_photo_viewer_opt_in_stays_commented(self):
        source = (UI / "PhotoViewer.java").read_text()
        self.assertIn("// iBlur3FactoryFrostedLiquidGlass.setLiquidGlassEffectAllowed("
                      "LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));", source)

    def test_mode_specific_crop_preserves_on_and_covers_frost(self):
        for filename, on_margin in (("ChatActivity.java", 24), ("ChannelAdminLogActivity.java", 24)):
            body = method((UI / filename).read_text(), "private int getVisibleBlurredPositions(")
            with self.subTest(filename=filename):
                self.assertIn(f"dp(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? {on_margin} : 48)", body)
                self.assertIn("glassBackgroundSourceFrostedRenderNode.getVisiblePositions(positions, count, dp(48))", body)

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_production_chat_source_setup_is_independent_of_initial_lg_mode(self):
        chat = method((UI / "ChatActivity.java").read_text(), "public ChatActivity(Bundle args)")
        chat = chat[chat.index("navbarContentSourceWallpaper ="):chat.index("navbarContentDrawableFactory =")]
        admin = method((UI / "ChannelAdminLogActivity.java").read_text(), "public ChannelAdminLogActivity(TLRPC.Chat chat)")
        admin = admin[admin.index("navbarContentSourceWallpaper ="):admin.index("navbarContentDrawableFactory.setLinkedViewsRef")]
        run_java("""public class ToggleHarness {
          static class LiteMode { static boolean enabled; }
          static class Build { static class VERSION { static int SDK_INT=33; }
            static class VERSION_CODES { static final int S=31; } }
          static class SharedConfig { static boolean blur=true;
            static boolean chatBlurEnabled(){return blur;} }
          static class AndroidUtilities { static int navigationBarHeight=24, statusBarHeight=24; }
          static int dp(int n){return n;}
          static class DownscaleScrollableNoiseSuppressor {
            static final int DRAW_GLASS=-2, DRAW_FROSTED_GLASS=-3;
          }
          static class BlurredBackgroundSourceWrapped {}
          static class BlurredBackgroundSourceRenderNode {
            int selector; Object under;
            BlurredBackgroundSourceRenderNode(Object fallback){}
            void setOnDrawablesRelativePositionChangeListener(Runnable listener){}
            void setScrollableNoiseSuppressor(DownscaleScrollableNoiseSuppressor s,int i){selector=i;}
            void setUnderSource(Object s){under=s;}
          }
          static class BlurredBackgroundDrawableViewFactory {
            final Object source; boolean capable;
            BlurredBackgroundDrawableViewFactory(Object s){source=s;}
            void setLiquidGlassEffectAllowed(boolean c){capable=c;}
          }
          BlurredBackgroundSourceWrapped navbarContentSourceWallpaper;
          BlurredBackgroundSourceRenderNode glassBackgroundSourceRenderNode, glassBackgroundSourceFrostedRenderNode;
          BlurredBackgroundDrawableViewFactory glassBackgroundDrawableFactory,
              glassBackgroundDrawableFactoryFrosted, navbarContentDrawableFactory;
          DownscaleScrollableNoiseSuppressor scrollableViewNoiseSuppressor;
          int recommendedAdditionalSizeY;
          void invalidateMergedVisibleBlurredPositionsAndSourcesPositions(){}
          void initChat(){""" + chat + """}
          void initAdmin(){""" + admin + """}
          static void check(boolean b){if(!b)throw new AssertionError();}
          public static void main(String[] args){
            for(boolean admin:new boolean[]{false,true}){
              for(boolean initial:new boolean[]{false,true}){
                LiteMode.enabled=initial;
                ToggleHarness h=new ToggleHarness();
                if(admin)h.initAdmin(); else h.initChat();
                Object clear=h.glassBackgroundSourceRenderNode, frost=h.glassBackgroundSourceFrostedRenderNode;
                Object factory=h.glassBackgroundDrawableFactory;
                check(clear!=null && frost!=null && clear!=frost);
                check(h.glassBackgroundSourceRenderNode.selector==-2);
                check(h.glassBackgroundSourceFrostedRenderNode.selector==-3);
                check(h.glassBackgroundSourceRenderNode.under==h.navbarContentSourceWallpaper);
                check(h.glassBackgroundDrawableFactory.source==clear);
                check(h.glassBackgroundDrawableFactoryFrosted.source==frost);
                check(h.recommendedAdditionalSizeY==(admin?48:24));
                // Both starting modes retain capable factories for repeated live toggles.
                for(boolean enabled:new boolean[]{true,false,true,false}){
                  LiteMode.enabled=enabled;
                  check(h.glassBackgroundDrawableFactory.capable);
                  check(h.glassBackgroundDrawableFactoryFrosted.capable);
                  check(h.glassBackgroundSourceRenderNode==clear);
                  check(h.glassBackgroundDrawableFactory==factory);
                }
              }
              for(int api:new int[]{30,33}){
                Build.VERSION.SDK_INT=api; SharedConfig.blur=api==30;
                ToggleHarness h=new ToggleHarness();
                if(admin)h.initAdmin(); else h.initChat();
                check(h.glassBackgroundSourceRenderNode==null);
                check(h.glassBackgroundSourceFrostedRenderNode==null);
                check(h.scrollableViewNoiseSuppressor==null);
                check(h.glassBackgroundDrawableFactory.source==h.navbarContentSourceWallpaper);
              }
              Build.VERSION.SDK_INT=33; SharedConfig.blur=true;
            }
          }
        }""")

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_picker_fade_updates_same_drawable_in_both_directions(self):
        source = (UI / "DialogsActivity.java").read_text().split(
            "BlurredBackgroundWithFadeDrawable fadeDrawable =", 1)[1]
        draw = method(source, "public void draw(@NonNull Canvas canvas)")
        run_java("""class FadeBase {
          int height, draws; boolean opaque;
          void setFadeHeight(int h,boolean o){height=h;opaque=o;}
          public void draw(ToggleHarness.Canvas c){draws++;}
        }
        public class ToggleHarness extends FadeBase {
          @interface NonNull {} static class Canvas {}
          static int dp(int n){return n;}
          static class SharedConfig { static boolean blur=true;
            static boolean chatBlurEnabled(){return blur;} }
          static class LiteMode { static final int FLAG_LIQUID_GLASS=1; static boolean enabled;
            static boolean isEnabled(int f){return enabled;} }
        """ + draw + """
          public static void main(String[] args){
            ToggleHarness h=new ToggleHarness();
            for(boolean blur:new boolean[]{true,false,true}){
              SharedConfig.blur=blur;
              for(boolean lg:new boolean[]{false,true,false,true}){
                LiteMode.enabled=lg; h.draw(new Canvas());
                boolean expected=!blur||lg;
                if(h.height!=(expected?72:40)||h.opaque!=expected)throw new AssertionError();
              }
            }
            if(h.draws!=12)throw new AssertionError();
          }
        }""")


if __name__ == "__main__":
    unittest.main()
