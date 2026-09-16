const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),cp=require('node:child_process'),assert=require('node:assert/strict');
const src=fs.readFileSync(path.resolve(__dirname,'../../main/java/org/telegram/messenger/MediaController.java'),'utf8');
const start=src.indexOf('        public void rebuildPhoto(boolean highQuality)');
let end=src.indexOf('{',start),depth=1;
while(depth&&++end<src.length){if(src[end]==='{')depth++;if(src[end]==='}')depth--;}
const method=src.slice(start,end+1);
const java=`
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
public class PhotoRebuildTest {
 static Path dir; static int mode,errors; static ArrayList<Bitmap> allocated=new ArrayList<>();
 String path,filterPath,imagePath,fullPaintPath; Object cropState;
 static class Pair<A,B>{A first;B second;Pair(A a,B b){first=a;second=b;}}
 static class Bitmap {
  enum CompressFormat{JPEG} enum Config{ARGB_8888} boolean recycled;
  Bitmap(){allocated.add(this);} int getWidth(){if(recycled)throw new AssertionError("recycled read");return 8;}int getHeight(){return getWidth();}
  boolean isRecycled(){return recycled;} void recycle(){if(recycled)throw new AssertionError("double recycle");recycled=true;}
  boolean compress(CompressFormat f,int q,OutputStream out)throws IOException{out.write(1);if(mode==5)return false;if(mode==6)throw new IOException("write failure");return true;}
  static Bitmap createBitmap(int w,int h,Config c){return new Bitmap();}
 }
 static class BitmapFactory {static Bitmap decodeFile(String p){return mode==2?null:new Bitmap();}static Bitmap decodeFile(String p,Object o){return mode==1?null:new Bitmap();}}
 static class StoryEntry {static Bitmap getScaledBitmap(Function<Object,Bitmap> f,int a,int b,boolean c,boolean d){return f.apply(null);}}
 static class AndroidUtilities {static int getPhotoSize(boolean q){return 1280;}static Pair<Integer,Integer> getImageOrientation(String p){return new Pair<>(mode==8?90:0,0);}}
 static class PhotoViewer {static Bitmap createCroppedBitmap(Bitmap b,Object c,int[] t,boolean m){if(b==null)throw new AssertionError("null crop");return mode==3?null:mode==7?b:new Bitmap();}}
 static class Matrix {void postRotate(int x){}void postScale(int x,int y){}}
 static class Bitmaps {static Bitmap createBitmap(Bitmap b,int x,int y,int w,int h,Matrix m,boolean f){return b;}}
 static class Paint {static final int ANTI_ALIAS_FLAG=1,FILTER_BITMAP_FLAG=2;Paint(int f){}}
 static class Canvas {Canvas(Bitmap b){}void drawBitmap(Bitmap b,int x,int y,Paint p){b.getWidth();}void scale(float a,float b){}}
 static class TLRPC {static class PhotoSize{}}
 static class ImageLoader {static TLRPC.PhotoSize scaleAndSaveImage(Bitmap b,Bitmap.CompressFormat f,int w,int h,int q,boolean z,int a,int c)throws IOException{b.getWidth();if(mode==4)return null;Files.write(dir.resolve("saved.jpg"),new byte[]{1});return new TLRPC.PhotoSize();}}
 static class UserConfig {static int selectedAccount;}
 static class FileLoader {static FileLoader getInstance(int a){return new FileLoader();}File getPathToAttach(TLRPC.PhotoSize s,boolean b){return dir.resolve("saved.jpg").toFile();}}
 static class FileLog {static void e(Exception e){errors++;}}
 static String getTempFileAbsolutePath(){return dir.resolve("painted.jpg").toString();}
 ${method}
 public static void main(String[] args)throws Exception{
  dir=Files.createTempDirectory("photo-rebuild-cases");
  for(mode=0;mode<=8;mode++){
   PhotoRebuildTest p=new PhotoRebuildTest();p.path=dir.resolve("source.jpg").toString();p.imagePath=dir.resolve("previous.jpg").toString();
   Files.write(Path.of(p.path),new byte[]{9});Files.write(Path.of(p.imagePath),new byte[]{7});
   if(mode==2||mode==5||mode==6||mode==7)p.fullPaintPath=dir.resolve("paint.png").toString();
   if(mode==1||mode==3||mode==7)p.cropState=new Object();
   allocated.clear();String previous=p.imagePath;p.rebuildPhoto(true);
   boolean failure=mode>=1&&mode<=6;
   if(failure){if(!previous.equals(p.imagePath)||!Files.exists(Path.of(previous)))throw new AssertionError("lost previous photo: "+mode);if(Files.exists(dir.resolve("painted.jpg")))throw new AssertionError("partial output retained");}
   else if(previous.equals(p.imagePath)||Files.exists(Path.of(previous))||Files.size(Path.of(p.imagePath))==0)throw new AssertionError("replacement failed: "+mode);
   for(Bitmap b:allocated)if(!b.recycled)throw new AssertionError("bitmap leak: "+mode);
   if(!Files.exists(Path.of(p.path)))throw new AssertionError("source deleted");
   if(!failure)Files.delete(Path.of(p.imagePath));
  }
  if(errors!=1)throw new AssertionError("expected caught write error");
  System.out.println("PASS: actual rebuildPhoto handles decode/crop/save/write failures, alias bitmaps, successful replacement and cleanup");
 }
}`;
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'nimarko-photo-test-'));
fs.writeFileSync(path.join(dir,'PhotoRebuildTest.java'),java);
const c=cp.spawnSync('javac',[path.join(dir,'PhotoRebuildTest.java')],{encoding:'utf8'});assert.equal(c.status,0,c.stderr);
const r=cp.spawnSync('java',['-cp',dir,'PhotoRebuildTest'],{encoding:'utf8'});assert.equal(r.status,0,r.stderr);process.stdout.write(r.stdout);
