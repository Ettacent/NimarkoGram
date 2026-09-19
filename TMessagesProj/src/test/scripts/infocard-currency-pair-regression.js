const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java/app/nimarkogram/messenger/infocards');
const config = fs.readFileSync(path.join(root, 'InfoCardsConfig.java'), 'utf8');
const crypto = fs.readFileSync(path.join(root, 'CryptoCard.java'), 'utf8');
const prefs = fs.readFileSync(path.join(root, 'preferences/InfoCardsPreferencesActivity.java'), 'utf8');
function method(source, signature) {
    const start = source.indexOf(signature);assert(start >= 0);
    let depth=0;
    const re=/\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|[{}]/g;
    re.lastIndex=source.indexOf('{',start);
    for(let t;(t=re.exec(source));){
        if(t[0]==='{')depth++;
        else if(t[0]==='}'&&--depth===0)return source.slice(start,re.lastIndex);
    }
    throw Error(signature);
}
assert(crypto.includes('if (!InfoCardsConfig.isTargetCurrencyAllowed(getCardId(), ccy)) continue;'));
assert(prefs.includes('InfoCardsConfig.isTargetCurrencyAllowed(pillId, currency)'));
assert(prefs.includes('InfoCardsConfig.setTargetCurrency(pillId, currencies.get(i))'));
const java=`import java.util.*;
public class CurrencyPairTest {
 static final String AUTO="AUTO";
 static class InfoCardType {static InfoCardType USD=new InfoCardType();int id=4;}
 static class InfoCardsConfig {
  ${method(config,'public static boolean isTargetCurrencyAllowed(')}
  ${method(config,'private static String normalizeTargetCurrency(')}
 }
 ${method(crypto,'private static String resolveCurrency(')}
 ${method(crypto,'private static String resolveCardCurrency(')}
 ${method(crypto,'private static boolean isValidCurrency(')}
 static void eq(String a,String b){if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
 public static void main(String[] args){
  eq(InfoCardsConfig.normalizeTargetCurrency(4," usd "),"AUTO");
  eq(InfoCardsConfig.normalizeTargetCurrency(2," usd "),"USD");
  eq(InfoCardsConfig.normalizeTargetCurrency(3,"USD"),"USD");
  Locale.setDefault(Locale.US);
  eq(resolveCardCurrency(4,"AUTO"),"EUR");eq(resolveCardCurrency(3,"AUTO"),"USD");
  eq(resolveCardCurrency(4,"USD"),"EUR");eq(resolveCardCurrency(4,"RUB"),"RUB");
  Locale.setDefault(new Locale("ru","RU"));eq(resolveCardCurrency(4,"AUTO"),"RUB");
  Locale.setDefault(Locale.ROOT);eq(resolveCardCurrency(4,"AUTO"),"EUR");
  eq(resolveCardCurrency(2,"USD"),"USD");
  System.out.println("PASS: USD/USD excluded from both pickers, saved USD normalization, US/RU/missing locale, crypto USD preserved");
 }
}`;
const tmp=fs.mkdtempSync(path.join(os.tmpdir(),'nimarko-currency-pair-'));
try {
    fs.writeFileSync(path.join(tmp,'CurrencyPairTest.java'),java);
    cp.execFileSync('javac',['CurrencyPairTest.java'],{cwd:tmp});
    process.stdout.write(cp.execFileSync('java',['CurrencyPairTest'],{cwd:tmp,encoding:'utf8'}));
} finally {fs.rmSync(tmp,{recursive:true,force:true});}
