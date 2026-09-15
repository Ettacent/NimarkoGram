const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const src = fs.readFileSync(path.resolve(__dirname, '../../main/java/org/telegram/ui/ProfileActivity.java'), 'utf8');
const start = src.indexOf('private void createProfileMoreMenu()');
const end = src.indexOf('\n    /**', start);
const method = src.slice(start,end);
assert(start>0 && end>start);
const ids = method.match(/final int\[\] extraIds = \{([\s\S]*?)\};/)[1].split(',').map(s=>s.trim().split('.').pop());
assert.equal(new Set(ids).size,ids.length);
for (const id of ['gift_premium','block_contact','report','leave_group','autoDeleteItem','gallery_menu_save'])
 assert(!ids.includes(id),id+' must stay in the main menu');
assert(method.includes('otherItem.getSubItem(id)'));
assert(method.includes('content.addView(item,'),'reuse original actions, permissions, visibility and tags');
assert(method.includes('hasVisibleItems |= item.getVisibility() == View.VISIBLE'));
assert(method.includes('if (!hasVisibleItems) return'));
assert(method.includes('insertion = i + 1'));
assert(method.includes('more.setRightIcon(0)'));
assert(method.includes('getSwipeBack().closeForeground()'));
const build=src.slice(src.indexOf('private void createActionBarMenu(boolean animated)'), start);
assert(build.indexOf('removeView(profileMoreMenu)') < build.indexOf('otherItem.removeAllSubItems()'));
assert(build.indexOf('createProfileMoreMenu();') > build.indexOf('nimarkoRebuildProfilePluginsMenu();'));
const auto=src.slice(src.indexOf('private void createAutoDeleteItem(Context context)'));
assert(auto.indexOf('addSwipeBackItem(0, autoDeleteItemDrawable') < auto.indexOf('otherItem.addColoredGap();'));
for(const withAuto of [false,true])for(const self of [false,true])for(const extraVisible of [false,true]){
 const rows=[...(withAuto?['auto','gap']:[]),'gift','block','gap','OPTION_USER_INFO','report'];
 const extras=rows.filter(id=>ids.includes(id));
 const root=extraVisible?rows.filter(id=>!ids.includes(id)):rows.slice();
 if(extraVisible){let i=root.indexOf('gap')+1;if(i===0){root.push('gap');i=root.length;}root.splice(i,0,'more');}
 if(extraVisible)assert.equal(root[root.indexOf('gap')+1],'more');
 if(withAuto){assert.equal(root[0],'auto');assert.equal(root[1],'gap');}
 assert(root.includes('gift')&&root.includes('block')&&root.includes('report'));
 assert.equal(root.filter(x=>x==='more').length,extraVisible?1:0);
}
console.log('PASS: profile menu grouping contracts, second-section placement, visibility, rebuild cleanup and unchanged primary actions (not device UI tests)');
