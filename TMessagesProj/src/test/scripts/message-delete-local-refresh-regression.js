const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),cp=require('node:child_process'),assert=require('node:assert/strict');
const src=fs.readFileSync(path.resolve(__dirname,'../../main/java/org/telegram/ui/ChatActivity.java'),'utf8');
const marker='private static int[] singleDeletionRefreshRange(';
const start=src.indexOf(marker);let end=src.indexOf('{',start),depth=1;
while(depth&&++end<src.length){if(src[end]==='{')depth++;if(src[end]==='}')depth--;}
const method=src.slice(start,end+1);assert(start>0);
assert(src.includes('size == 1 && newGroups == null'));
assert(src.includes('!chatAdapter.isFiltered && range != null'));
assert(src.includes('remaining.get(i).getGroupId() != 0'));
assert(src.includes('updateVisibleRows(deletionRowsRebound);'));
const java=`import java.util.*;
public class DeleteRangeTest {
 ${method}
 static void check(boolean v){if(!v)throw new AssertionError();}
 public static void main(String[]args){
  int cases=0;
  for(int n=1;n<=200;n++)for(int removed=0;removed<n;removed++)for(int k=1;k<=Math.min(3,n-removed);k++){
   ArrayList<Integer> positions=new ArrayList<>(),old=new ArrayList<>(),now;
   for(int i=0;i<n;i++)old.add(i);
   now=new ArrayList<>(old);
   for(int i=0;i<k;i++){positions.add(removed+4);now.remove(removed);}
   int[] range=singleDeletionRefreshRange(positions,4,4,now.size());
   if(now.isEmpty()){check(range==null);continue;}
   check(range!=null&&range[0]>=0&&range[1]<=now.size()&&range[1]-range[0]<=4);
   for(int i=0;i<now.size();i++){
    int before=now.get(i);boolean changed=false;
    for(int offset=-2;offset<=2;offset++){
     Integer a=before+offset<0||before+offset>=n?null:old.get(before+offset);
     Integer b=i+offset<0||i+offset>=now.size()?null:now.get(i+offset);
     if(!Objects.equals(a,b))changed=true;
    }
    if(changed)check(i>=range[0]&&i<range[1]);
   }
   cases++;
  }
  check(singleDeletionRefreshRange(new ArrayList<>(List.of(4)),4,5,100)==null);
  check(singleDeletionRefreshRange(new ArrayList<>(),4,4,100)==null);
  check(singleDeletionRefreshRange(new ArrayList<>(List.of(3)),4,4,100)==null);
  System.out.println("PASS: "+cases+" single message/date/conversion removals; all changed two-hop neighbors covered, at most four rows rebound");
 }
}`;
const tmp=fs.mkdtempSync(path.join(os.tmpdir(),'nimarko-delete-range-'));
try{fs.writeFileSync(path.join(tmp,'DeleteRangeTest.java'),java);cp.execFileSync('javac',['DeleteRangeTest.java'],{cwd:tmp});process.stdout.write(cp.execFileSync('java',['DeleteRangeTest'],{cwd:tmp}));}finally{fs.rmSync(tmp,{recursive:true,force:true});}
