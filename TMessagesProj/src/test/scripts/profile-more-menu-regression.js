const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const src = fs.readFileSync(path.resolve(__dirname, '../../main/java/org/telegram/ui/ProfileActivity.java'), 'utf8');
const start = src.indexOf('private void createProfileMoreMenu()');
const end = src.indexOf('\n    /**', start);
const method = src.slice(start,end);
assert(start>0 && end>start);
const groupSource = method.match(/final int\[\]\[\] extraGroups = \{([\s\S]*?)\n        \};/)[1];
const groups = [...groupSource.matchAll(/\{([^{}]+)\}/g)].map(m => m[1].split(',').map(s=>s.trim().split('.').pop()));
const ids = groups.flat();
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
assert(method.includes('if (groupVisible)'));
assert(method.includes('new ActionBarPopupWindow.GapView(context, resourcesProvider,'));
assert(method.includes('Theme.key_actionBarDefaultSubmenuSeparator'));
assert(method.includes('LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8)'));
assert(!method.includes('new TextView'), 'no section headings');
for (let mask = 0; mask < (1 << ids.length); mask++) {
 const visible = new Set(ids.filter((_, i) => mask & (1 << i)));
 const rows = ['back'];
 for (const group of groups) {
  const items = group.filter(id => visible.has(id));
  if (items.length) rows.push('gap', ...items);
 }
 assert.notEqual(rows.at(-1), 'gap');
 assert(!rows.some((r,i) => r==='gap' && rows[i+1]==='gap'));
 assert.deepEqual(rows.filter(r=>r!=='back'&&r!=='gap'), ids.filter(id=>visible.has(id)));
 assert.equal(rows.filter(r=>r==='gap').length,groups.filter(g=>g.some(id=>visible.has(id))).length);
}
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
