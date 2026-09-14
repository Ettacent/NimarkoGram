const fs = require('node:fs'), path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../../..');
const file = 'TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java';
const profile = fs.readFileSync(path.join(root, file), 'utf8');
const panel = fs.readFileSync(path.join(root, 'TMessagesProj/src/main/java/app/nimarkogram/messenger/notifications/NotificationInlinePanel.java'), 'utf8');
const notices = fs.readFileSync(path.join(root, 'TMessagesProj/src/main/java/app/nimarkogram/messenger/notifications/NimarkoInAppNotifications.java'), 'utf8');
const placement = fs.readFileSync(path.join(root, 'TMessagesProj/src/main/java/app/nimarkogram/messenger/notifications/ProfileNotificationPlacement.java'), 'utf8');
function method(source, signature) {
    const start = source.indexOf(signature);assert(start >= 0, signature);
    let end = source.indexOf('{', start), depth = 1;
    while (depth && ++end < source.length) {if (source[end] === '{') depth++;if (source[end] === '}') depth--;}
    return source.slice(start, end + 1);
}
function nativeSpacer(source) {
    const start = source.indexOf('case VIEW_TYPE_EMPTY:');
    return source.slice(start, source.indexOf('case VIEW_TYPE_HEADER_EMPTY:', start)).replace(/\s+/g, '');
}
const baseline = `
case VIEW_TYPE_EMPTY:
case VIEW_TYPE_EMPTY2: {
    final int height = viewType == VIEW_TYPE_EMPTY2 ? dp(12) : dp(6);
    view = new View(mContext) {
        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            );
        }
    };
    view.setTag(RecyclerListView.TAG_NOT_SECTION);
    break;
}`;
assert.equal(nativeSpacer(profile), baseline.replace(/\s+/g, ''), 'native first-row measurement must be restored exactly');
assert.doesNotMatch(profile, /ProfileNotificationSpacer|profileNotificationHeight|setProfileNotificationHeight|withHeightListener|setNotificationOffset/);
assert.doesNotMatch(panel + notices, /OverlayContent|retainedOverlayHeight|getOverlayReservedHeight|heightListener/);
const factory = method(profile, 'public org.telegram.ui.Components.AnimatedLinearLayout getInAppNotificationPanel()');
assert.match(factory, /NotificationInlinePanel\(this, host\)\s*\.withOverlayAnchor\(this::getProfileNotificationTop\)/);
assert.match(factory, /withCompactReservation\(this::setProfileNotificationReservation, notificationPlacement::prepareForDraw\)/);
assert.doesNotMatch(factory, /requestLayout|setPadding|scrollTo/);
assert.match(method(profile, 'private int getProfileNotificationTop()'), /notificationPlacement.getAnchorBottom\(toolbarHeight\)/);
assert.doesNotMatch(method(profile, 'private int getProfileNotificationTop()'), /extraHeight|set[A-Z]|requestLayout|scrollTo|notificationInlinePanel/);
assert.match(placement, /outRect.bottom = reservedHeight/);
assert.doesNotMatch(placement, /setTranslationY|setPadding|setLayoutParams|extraHeight|listContentHeight|positionToOffset/);
assert.doesNotMatch(method(profile, 'private class ClippedListView'), /setTranslationY|onMeasure|requestLayout|notification/i);
assert.match(method(profile, 'public View createView(Context context)'), /notificationInlinePanel.release\(\)/);
assert.match(method(profile, 'public void onFragmentDestroy()'), /notificationInlinePanel.release\(\)/);
assert.match(panel, /boolean isOverlay\(\)\s*\{\s*return contents.length == 0;/);
const update = method(panel, 'private void updateReservedHeight()');
assert.match(update, /for \(int i = 0; i < contents.length; i\+\+\)/);
assert.match(update, /if \(isOverlay\(\)\)[\s\S]*?invalidate\(\)[\s\S]*?else\s*\{\s*root.requestLayout\(\)/);
assert.doesNotMatch(update, /getFragmentView\(\)\.(?:set|scroll|requestLayout)/);
const banner = notices.slice(notices.indexOf('private static final class Banner'));
assert.match(method(banner, 'public boolean dispatchTouchEvent(MotionEvent e)'), /ACTION_DOWN[\s\S]*?requestDisallowInterceptTouchEvent\(true\)/);
assert.match(method(banner, 'public boolean dispatchTouchEvent(MotionEvent e)'), /ACTION_CANCEL[\s\S]*?requestDisallowInterceptTouchEvent\(false\)/);
assert.doesNotMatch(method(banner, 'void setPullOffset(float offset)'), /ProfileActivity|listView|avatar|extraHeight/);
console.log('PASS: native header spacer unchanged; compact reservation uses a separate bottom decoration and row anchor; gesture ownership and cleanup retained');
