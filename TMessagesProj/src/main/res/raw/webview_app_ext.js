/*
 *  Telegram-Android browser extension for Mini Apps
 *
 *  # Gestures
 *  This script captures whether touch event is consumed by a website, to otherwise apply
 *  down or right gesture. Use `event.preventDefault()` at `touchstart` to prevent those gestures.
 *  It is recommended to do `event.preventDefault()` when dragging or swiping is expected to be
 *  handled by a website.
 *
 *  Since some websites don't do that, the script also captures `style` and `class` changes to
 *  hierarchy of a touch element, and does equivalent of `preventDefault` if those changes happen
 *  while `touchstart` or `touchmove` events.
 */

if (!window.__tg__webview_set) {
    window.__tg__webview_set = true;
    (function () {
        const DEBUG = $DEBUG$;

        // Touch gestures hacks
        let prevented = false;
        let awaitingResponse = false;
        let touchElement = null;
        let mutatedWhileTouch = false;
        let whiletouchstart = false, whiletouchmove = false;
        let mutationObserver = null;
        let touchSequence = 0;
        const isParentOf = (element, parent) => {
            while (element && parent) {
                if (element === parent) return true;
                element = element.parentElement;
            }
            return false;
        };
        const startMutationObserver = () => {
            if (!mutationObserver) {
                mutationObserver = new MutationObserver(mutationList => {
                    if (!touchElement) return;
                    for (const mutation of mutationList || []) {
                        const target = mutation && mutation.target;
                        if (target && target !== document.body && target !== document.documentElement && isParentOf(touchElement, target)) {
                            if (DEBUG) {
                                console.log('tgbrowser mutation detected', mutationList);
                            }
                            mutatedWhileTouch = true;
                            break;
                        }
                    }
                });
            }
            mutationObserver.disconnect();
            mutationObserver.observe(document, { attributes: true, attributeFilter: ['style', 'class'], subtree: true });
        };
        const finishTouch = () => {
            if (mutationObserver) mutationObserver.disconnect();
            touchElement = null;
            whiletouchstart = false;
            whiletouchmove = false;
            const finishedSequence = touchSequence;
            setTimeout(() => {
                if (finishedSequence === touchSequence) {
                    awaitingResponse = false;
                    prevented = false;
                    mutatedWhileTouch = false;
                }
            }, 32);
        };
        document.addEventListener('touchstart', e => {
            touchSequence++;
            touchElement = e.target;
            awaitingResponse = true;
            whiletouchstart = true;
            startMutationObserver();
        }, true);
        document.addEventListener('touchstart', e => {
            whiletouchstart = false;
        }, false);
        const atLeft = e => !e || e == document || e.scrollLeft <= 0 && atLeft(e.parentNode);
        const atTop = e => !e || e == document || e.scrollTop <= 0 && atTop(e.parentNode);
        document.addEventListener('touchmove', e => {
            whiletouchstart = false;
            whiletouchmove = true;
            if (awaitingResponse) {
                setTimeout(() => {
                    if (awaitingResponse) {
                        if (window.TelegramWebviewProxy) {
                            const allowScrollX = !prevented && atLeft(e.target) && (!window.visualViewport || window.visualViewport.offsetLeft == 0) && !mutatedWhileTouch;
                            const allowScrollY = !prevented && atTop(e.target)  && (!window.visualViewport || window.visualViewport.offsetTop == 0)  && !mutatedWhileTouch;
                            if (DEBUG) {
                                console.log('tgbrowser allowScroll sent after "touchmove": x=' + allowScrollX + ' y=' + allowScrollY, { e, prevented, mutatedWhileTouch });
                            }
                            window.TelegramWebviewProxy.postEvent('web_app_allow_scroll', JSON.stringify([ allowScrollX, allowScrollY ]));
                        }
                        prevented = false;
                        awaitingResponse = false;
                    }
                    mutatedWhileTouch = false;
                }, 16);
            }
        }, true);
        document.addEventListener('touchmove', e => {
            whiletouchmove = false;
        }, false);
        document.addEventListener('touchend', finishTouch, true);
        document.addEventListener('touchcancel', finishTouch, true);
        document.addEventListener('scroll', e => {
            if (!e.target) return;
            const allowScrollX = e.target.scrollLeft == 0 && (!window.visualViewport || window.visualViewport.offsetLeft == 0) && !prevented && !mutatedWhileTouch;
            const allowScrollY = e.target.scrollTop == 0  && (!window.visualViewport || window.visualViewport.offsetTop == 0)  && !prevented && !mutatedWhileTouch;
            if (DEBUG) {
                console.log('tgbrowser scroll on' + e.target + ' scrollLeft=' + e.target.scrollLeft + ' scrollTop=' + e.target.scrollTop);
            }
            if (awaitingResponse) {
                if (window.TelegramWebviewProxy) {
                    if (DEBUG) {
                        console.log('tgbrowser allowScroll sent after "scroll": x=' + allowScrollX + ' y=' + allowScrollY, { e, prevented, mutatedWhileTouch, scrollLeft: e.target.scrollLeft, scrollTop: e.target.scrollTop });
                    }
                    window.TelegramWebviewProxy.postEvent('web_app_allow_scroll', JSON.stringify([allowScrollX, allowScrollY]));
                }
                awaitingResponse = false;
            }
            prevented = false;
            mutatedWhileTouch = false;
        }, true);
        if (typeof TouchEvent !== 'undefined') {
            const originalPreventDefault = TouchEvent.prototype.preventDefault;
            TouchEvent.prototype.preventDefault = function () {
                prevented = true;
                originalPreventDefault.call(this);
            };
            const originalStopPropagation = TouchEvent.prototype.stopPropagation;
            TouchEvent.prototype.stopPropagation = function () {
                if (this.type === 'touchmove') {
                    whiletouchmove = false;
                } else if (this.type === 'touchstart') {
                    whiletouchstart = false;
                }
                originalStopPropagation.call(this);
            };
        }
    })();
};
