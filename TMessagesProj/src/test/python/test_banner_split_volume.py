"""Verify split banner gain and unchanged ordinary VideoPlayer behaviour."""
from pathlib import Path
import unittest
from test_gif_loop_transition import method
from test_sender_infocard_transitions import run_java

JAVA = Path(__file__).resolve().parents[2] / 'main/java'


class SplitVolumeTests(unittest.TestCase):
    def test_gain_and_envelope_are_independent(self):
        source = (JAVA / 'org/telegram/ui/Components/VideoPlayer.java').read_text()
        bodies = '\n'.join(method(source, s) for s in
            ('public void setVolume(float volume)', 'public void setPlaybackEnvelope(',
             'public float getVolume()'))
        run_java(r'''
public class Transitions {
 interface SourceVolumeController {void setSourceVolume(float v);float getSourceVolume();}
 static class Factory implements SourceVolumeController {
  float gain;public void setSourceVolume(float v){gain=v;}
  public float getSourceVolume(){return gain;}
 }
 static class Player {
  float gain;void setVolume(float v){gain=v;}float getVolume(){return gain;}
 }
 Object customRenderersFactory;float customInitialVolume;
 Player player=new Player(),audioPlayer;
 BODY
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Transitions t=new Transitions();Factory f=new Factory();t.customRenderersFactory=f;
  t.setVolume(.05f);check(f.gain==.05f&&t.player.gain==0);
  for(int i=0;i<=100;i++){t.setPlaybackEnvelope(i/100f);check(f.gain==.05f&&t.player.gain==i/100f);}
  t.setPlaybackEnvelope(0);check(t.getVolume()==.05f&&t.player.gain==0);
  t.player=null;t.setPlaybackEnvelope(.3f);t.setVolume(.02f);
  check(t.customInitialVolume==.3f&&f.gain==.02f);
  t=new Transitions();t.audioPlayer=new Player();t.setVolume(.4f);
  check(t.player.gain==.4f&&t.audioPlayer.gain==.4f);
  t.setPlaybackEnvelope(0);check(t.player.gain==.4f); // non-banner player is untouched
 }
}
'''.replace('BODY', bodies))

    def test_sink_does_not_bake_envelope_into_pcm(self):
        source = (JAVA / 'app/nimarkogram/messenger/banners/BannerAudioRenderersFactory.java').read_text()
        sink_gain = method(source, 'public void setVolume(float gain)')
        self.assertIn('super.setVolume(gain);', sink_gain)
        self.assertNotIn('setGain(', sink_gain)
        self.assertIn('processor.setGain(sourceVolume)', method(source, 'public synchronized void setSourceVolume('))
