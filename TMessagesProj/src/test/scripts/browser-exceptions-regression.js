const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../../..');
const read = name => fs.readFileSync(path.join(root, 'TMessagesProj/src/main/java', name), 'utf8');
const source = read('org/telegram/messenger/MessagesController.java');
function method(signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, signature);
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|[{}]/g;
    tokens.lastIndex = source.indexOf('{', start);
    let depth = 0;
    for (let token; (token = tokens.exec(source));) {
        if (token[0] === '{') depth++;
        if (token[0] === '}' && --depth === 0) return source.slice(start, tokens.lastIndex)
            .replaceAll('@NonNull ', '').replaceAll('app.nimarkogram.messenger.NimarkoConfig', 'Config');
    }
    throw new Error(signature);
}
const signatures = [
    'public boolean isWebBrowserInAppEnabled()',
    'public boolean isWebBrowserOpenInApp(String url)',
    'public boolean isWebBrowserOpenInApp(String url, boolean preferInApp)',
    'private static boolean hasWebBrowserException(@NonNull TL_account.TL_webBrowserSettings',
    'private static boolean isWebBrowserOpenInExternal(',
    'private static boolean hasWebBrowserException(ArrayList',
    'public void toggleWebBrowserInAppEnabled()',
];
const methods = signatures.map(method).join('\n');
const harness = `import java.util.*;
public class BrowserExceptionsTest {
 static class Config {static boolean inappBrowser;static void setInappBrowser(boolean b){inappBrowser=b;}}
 static class TextUtils {
  static boolean isEmpty(String s){return s==null||s.isEmpty();}
  static boolean equals(String a,String b){return Objects.equals(a,b);}
 }
 static class AndroidUtilities {
  static String getHostAuthority(String s){try{return new java.net.URI(s).getHost();}catch(Exception e){return null;}}
 }
 static class TL_account {
  static class WebDomainException {String domain;WebDomainException(String d){domain=d;}}
  static class TL_webBrowserSettings {
   boolean open_external_browser,display_close_button=true;
   ArrayList<WebDomainException> external_exceptions=new ArrayList<>(),inapp_exceptions=new ArrayList<>();
  }
 }
 static class Controller {
  TL_account.TL_webBrowserSettings webBrowserSettings;int updates;boolean requestedExternal;
  void updateWebBrowserSettings(boolean external,boolean tabs){updates++;requestedExternal=external;if(webBrowserSettings!=null)webBrowserSettings.open_external_browser=external;}
  ${methods}
 }
 static void check(boolean b,String reason){if(!b)throw new AssertionError(reason);}
 public static void main(String[] args){
  for(Locale locale:new Locale[]{Locale.US,Locale.forLanguageTag("tr-TR")}){
   Locale.setDefault(locale);
   for(boolean local:new boolean[]{false,true})for(boolean external:new boolean[]{false,true}){
    Config.inappBrowser=local;Controller c=new Controller();
    check(c.isWebBrowserOpenInApp("https://example.org")==local,"cold local default");
    c.webBrowserSettings=new TL_account.TL_webBrowserSettings();c.webBrowserSettings.open_external_browser=external;
    boolean defaultInApp=local||!external;
    check(c.isWebBrowserOpenInApp("https://example.org")==defaultInApp,"preserve general browser mode");
    c.webBrowserSettings.external_exceptions.add(new TL_account.WebDomainException("SITE.ORG"));
    c.webBrowserSettings.inapp_exceptions.add(new TL_account.WebDomainException("inside.org"));
    c.webBrowserSettings.external_exceptions.add(null);
    c.webBrowserSettings.external_exceptions.add(new TL_account.WebDomainException(null));
    for(boolean article:new boolean[]{false,true}){
     for(String url:new String[]{"https://site.org/x", "https://sub.site.org", "SITE.ORG"})
      check(!c.isWebBrowserOpenInApp(url,article),"external exception must win: "+url);
     check(c.isWebBrowserOpenInApp("https://inside.org",article),"in-app exception");
     check(c.isWebBrowserOpenInApp("https://notsite.org",article)==(article||defaultInApp),"domain boundary");
     check(c.isWebBrowserOpenInApp("mailto:a@b.org",article)==(article||defaultInApp),"hostless URL");
     check(!c.isWebBrowserOpenInApp(null,article),"null URL");
    }
    Controller other=new Controller();other.webBrowserSettings=new TL_account.TL_webBrowserSettings();
    check(other.isWebBrowserOpenInApp("https://site.org"),"exceptions are account scoped");
    c.webBrowserSettings.external_exceptions.clear();
    check(c.isWebBrowserOpenInApp("https://site.org")==defaultInApp,"removed exception");
    c.toggleWebBrowserInAppEnabled();
    check(c.updates==1&&c.requestedExternal==defaultInApp,"toggle effective, not stale local state");
    check(c.isWebBrowserInAppEnabled()!=defaultInApp,"toggle takes effect");
   }
   Config.inappBrowser=true;Controller cold=new Controller();cold.toggleWebBrowserInAppEnabled();
   check(cold.updates==1&&cold.requestedExternal&&!Config.inappBrowser,"toggle before configuration loads");
  }
 }
}`;
const browser = read('org/telegram/messenger/browser/Browser.java');
assert.equal(browser.split('isWebBrowserOpenInApp(uri.toString(), isInstantViewOpen())').length - 1, 2);
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-browser-exceptions-'));
try {
    const file = path.join(dir, 'BrowserExceptionsTest.java');
    fs.writeFileSync(file, harness);
    cp.execFileSync('javac', [file]);
    cp.execFileSync('java', ['-cp', dir, 'BrowserExceptionsTest']);
    const broken = harness.replace('if (url == null) return false;',
        'if (url == null) return false; if (Config.inappBrowser) return true;');
    fs.writeFileSync(file, broken);
    cp.execFileSync('javac', [file]);
    const result = cp.spawnSync('java', ['-cp', dir, 'BrowserExceptionsTest'], {encoding:'utf8'});
    assert.notEqual(result.status, 0, 'old forced-default implementation must fail');
    assert(result.stderr.includes('external exception must win'));
    console.log('PASS: actual browser routing methods; defaults, both exceptions, articles, account isolation, removal, cold toggle, Turkish locale and old-code negative control');
} finally {
    fs.rmSync(dir, {recursive:true, force:true});
}
