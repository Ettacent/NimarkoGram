const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main');
const read = p => fs.readFileSync(path.join(root, p), 'utf8');
const source = read('java/app/nimarkogram/messenger/utils/chats/NimarkoChatMenuInjector.java');
const config = read('java/app/nimarkogram/messenger/NimarkoConfig.java');
const dialogs = read('java/org/telegram/ui/DialogsActivity.java');
const chat = read('java/org/telegram/ui/ChatActivity.java');
function block(marker, sourceText = source) {
    const source = sourceText;
    const start = source.indexOf(marker);
    assert(start >= 0, marker);
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|[{}]/g;
    tokens.lastIndex = source.indexOf('{', start);
    let depth = 0;
    for (let t; (t = tokens.exec(source));) {
        if (t[0] === '{') depth++;
        if (t[0] === '}' && --depth === 0) return source.slice(start, tokens.lastIndex);
    }
    throw new Error(marker);
}
const methods = ['public static ItemOptions createHeaderSubmenu(', 'public static void addHeaderAction(',
    'public static ActionBarMenuItem.Item attachHeaderSubmenu(', 'public static void injectAdminShortcuts('].map(marker => block(marker)).join('\n');
const move = block('public void moveLazyItemToStart(', read('java/org/telegram/ui/ActionBar/ActionBarMenuItem.java'));
assert(config.includes('iconReplacement = getIntSafe("iconReplacement", ICON_REPLACE_SOLAR)'));
assert(!config.includes('nmForcePlumpyOnce') && !config.includes('PLUMPY_DEFAULT_VERSION'));
assert(config.includes('putInt("iconReplacement", v)'));
assert(dialogs.includes('ItemOptions.makeOptions(this, optionsItem, true)'));
for (const item of ['Archived', 'Calls', 'ScanQR', 'CreateChannel', 'Gifts', 'ProxySettings']) {
    assert(dialogs.includes(`inject${item}(extras,`));
    assert(!dialogs.includes(`inject${item}(io,`));
}
assert(dialogs.includes('pluginOptions.add(iconRes, rec.text.toString()'));
assert(dialogs.includes('isPluginActive(rec.pluginId)'));
assert(dialogs.includes('pluginOptions.setOnDismiss(io::dismiss)'));
assert(dialogs.includes('extras.setOnDismiss(io::dismiss)'));
assert(dialogs.includes('io::closeSwipeback'));
const homeMenu=dialogs.slice(dialogs.indexOf('private void showItemOptions()'));
assert(homeMenu.indexOf('io.add(')===homeMenu.indexOf('io.add(R.drawable.ic_ab_other, getString(R.string.NM_Menu_More)'));
assert(!homeMenu.slice(0, homeMenu.indexOf('final Activity activity')).includes('setRightIcon'));
assert(chat.includes('moreActions.setRightIconVisibility(View.GONE)'));
assert(chat.includes('headerItem.moveLazyItemToStart(moreActions)'));
for (const item of ['nimarko_jump_to_begin', 'nimarko_saved_messages', 'nimarko_browser', 'nimarko_delete_all']) {
    assert(!chat.includes(`lazilyAddSubItem(${item},`));
    assert(new RegExp(`addHeaderAction\\(extraActions, headerItem, this,\\s*${item},`).test(chat));
}
assert(chat.includes('headerItem.lazilyAddSubItem(search, R.drawable.msg_search'));
for (const locale of ['values', 'values-ru', 'values-zh-rCN']) {
    const strings = read(`res/${locale}/strings.xml`);
    for (const key of ['NM_Menu_More', 'NM_Menu_Manage']) assert.equal(strings.split(`name="${key}"`).length, 2);
}
const names = [...new Set([...methods.matchAll(/R\.(drawable|string)\.(\w+)/g)].map(m=>`${m[1]}.${m[2]}`))];
let id = 1;
const resources = ['drawable', 'string'].map(type => `static class ${type}{${names.filter(n=>n.startsWith(type+'.')).map(n=>`static final int ${n.split('.')[1]}=${id++};`).join('')}}`).join('');
const ids = [...new Set([...methods.matchAll(/\bADMIN_OPTION_\w+/g)].map(m=>m[0]))];
const java = `import java.util.*;
public class CompactMenuTest {
 static class R{${resources}}
 ${ids.map((name, i)=>`static final int ${name}=${100+i};`).join('\n')}
 static String getString(int id){return "s"+id;}
 static class TLRPC{static class Chat{boolean admin,channel,megagroup,gigagroup,edit,boost;}}
 static class ChatObject{static boolean hasAdminRights(TLRPC.Chat c){return c.admin;}
  static boolean canChangeChatInfo(TLRPC.Chat c){return c.edit;}static boolean isChannel(TLRPC.Chat c){return c.channel;}
  static boolean isBoostSupported(TLRPC.Chat c){return c.boost;}}
 static class NimarkoConfig{static boolean adminsReactions,adminsPermissions,adminsAdministrators,adminsMembers,adminsStatistics,adminsRecentActions;}
 static class Swipe{int closes;void closeForeground(){closes++;}}
 static class Popup{Swipe swipe=new Swipe();Swipe getSwipeBack(){return swipe;}}
 static class ViewGroup{static class LayoutParams{static final int MATCH_PARENT=-1,WRAP_CONTENT=-2;}}
 static class ScrollView{ItemOptions child;ScrollView(Object context){}void setVerticalScrollBarEnabled(boolean value){}
  static class LayoutParams{LayoutParams(int width,int height){}}
  void addView(ItemOptions view,LayoutParams params){child=view;}}
 static class ActionBarMenuItem{Popup popup=new Popup();int closes;List<ItemOptions> submenus=new ArrayList<>();
  static class Item{}
  ArrayList<Item> lazyList=new ArrayList<>();
  ${move}
  Object getContext(){return null;}
  Popup getPopupLayout(){return popup;}void closeSubMenu(){closes++;}
  Item lazilyAddSwipeBackItem(int icon,Object drawable,String title,Object view){submenus.add(((ScrollView)view).child);return new Item();}}
 static class Handler{List<Integer> ids=new ArrayList<>();void onItemClick(int id){ids.add(id);}}
 static class Bar{Handler handler=new Handler();Handler getActionBarMenuOnItemClick(){return handler;}}
 static class ChatActivity{Bar bar=new Bar();Bar getActionBar(){return bar;}Object getResourceProvider(){return null;}}
 static class ItemOptions{List<Runnable> actions=new ArrayList<>();
  static ItemOptions swipeback(Popup p,Object provider){return new ItemOptions();}
  void add(int icon,CharSequence text,Runnable run){actions.add(run);}void addGap(){actions.add(null);}
  int getItemsCount(){return 0;}int getChildCount(){return actions.size();}ItemOptions getLinearLayout(){return this;}}
 ${methods}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] args){int cases=0;
  ActionBarMenuItem order=new ActionBarMenuItem();ActionBarMenuItem.Item first=new ActionBarMenuItem.Item(),more=new ActionBarMenuItem.Item(),last=new ActionBarMenuItem.Item();
  order.lazyList.add(first);order.lazyList.add(more);order.lazyList.add(last);
  order.moveLazyItemToStart(more);order.moveLazyItemToStart(more);order.moveLazyItemToStart(null);order.moveLazyItemToStart(new ActionBarMenuItem.Item());
  check(order.lazyList.equals(Arrays.asList(more,first,last)),"more first, stable other rows, idempotent and foreign-item safe");
  for(int mask=0;mask<64;mask++)for(int rights=0;rights<32;rights++){
   NimarkoConfig.adminsReactions=(mask&1)!=0;NimarkoConfig.adminsPermissions=(mask&2)!=0;
   NimarkoConfig.adminsAdministrators=(mask&4)!=0;NimarkoConfig.adminsMembers=(mask&8)!=0;
   NimarkoConfig.adminsStatistics=(mask&16)!=0;NimarkoConfig.adminsRecentActions=(mask&32)!=0;
   TLRPC.Chat c=new TLRPC.Chat();c.admin=(rights&1)!=0;c.edit=(rights&2)!=0;c.channel=(rights&4)!=0;
   c.megagroup=(rights&8)!=0;c.gigagroup=(rights&16)!=0;c.boost=c.channel;
   ActionBarMenuItem header=new ActionBarMenuItem();ChatActivity chat=new ChatActivity();
   injectAdminShortcuts(header,chat,c);
   int expected=c.admin?((NimarkoConfig.adminsReactions&&c.edit?1:0)+(NimarkoConfig.adminsPermissions?1:0)
    +(NimarkoConfig.adminsAdministrators?1:0)+(NimarkoConfig.adminsMembers?1:0)
    +(NimarkoConfig.adminsStatistics&&c.boost?1:0)+(NimarkoConfig.adminsRecentActions?1:0)):0;
   check(header.submenus.size()==(expected>0?1:0),"exactly one root admin item, no empty submenu");
   if(expected>0){ItemOptions sub=header.submenus.get(0);check(sub.actions.size()==expected+2,"all configured actions preserved");
    sub.actions.get(0).run();check(header.popup.swipe.closes==1&&header.closes==0,"back stays inside popup");
    for(int i=2;i<sub.actions.size();i++)sub.actions.get(i).run();
    check(header.closes==expected&&chat.bar.handler.ids.size()==expected,"dispatch and close");
    check(new HashSet<>(chat.bar.handler.ids).size()==expected,"no duplicate actions");
   }cases++;
  }
  System.out.println("PASS: "+cases+" admin/flag combinations, submenu dispatch/back, main/chat grouping, icon defaults and translations");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-compact-menu-'));
try {
    const file = path.join(dir, 'CompactMenuTest.java');fs.writeFileSync(file, java);
    cp.execFileSync('javac', [file]);
    const result = cp.spawnSync('java', ['-cp', dir, 'CompactMenuTest'], {encoding:'utf8'});
    assert.equal(result.status, 0, result.stderr);console.log(result.stdout.trim());
} finally {fs.rmSync(dir, {recursive:true, force:true});}
