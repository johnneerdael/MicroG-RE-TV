/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 *
 * Spatial D-pad navigation for web pages shown in a WebView on Android TV.
 *
 * Chromium only enables its own spatial navigation when the device lacks a touchscreen, which
 * many TV devices declare anyway. Without it the arrow keys only scroll and form fields or
 * buttons cannot be reached. This moves focus to the nearest focusable element in the pressed
 * direction, and lets OK (Enter) toggle checkboxes and radio buttons.
 */
(function () {
    if (window.__microgDpadNavigation) return;
    window.__microgDpadNavigation = true;

    var FOCUSABLE = 'a[href], area[href], button, input:not([type="hidden"]), select, textarea, iframe, ' +
        '[tabindex], [contenteditable=""], [contenteditable="true"], [role="button"], [role="link"], ' +
        '[role="checkbox"], [role="radio"], [role="switch"], [role="option"], [role="menuitem"], [role="tab"]';
    var TEXT_INPUT_TYPES = ['', 'text', 'email', 'password', 'search', 'tel', 'url', 'number'];
    var MESSAGE = '__microgDpadNavigation';
    // A frame that did not answer a hand-off (no copy of this script runs in it, or it was still
    // loading) is skipped for a while, as focusing it could trap the D-pad inside.
    var DEAD_FRAME_MS = 5000;
    // Elements that are controls themselves; any other focused element is a container whose
    // contents stay reachable.
    var CONTROL = 'a[href], area[href], button, input, select, textarea, iframe, [contenteditable=""], ' +
        '[contenteditable="true"], [role="button"], [role="link"], [role="checkbox"], [role="radio"], ' +
        '[role="switch"], [role="option"], [role="menuitem"], [role="tab"], [role="combobox"]';
    var STYLE_ID = 'microg-dpad-focus-style';
    // Widgets that move their own selection with the arrow keys (roving focus or active descendant)
    var COMPOSITE = '[role="menu"], [role="menubar"], [role="listbox"], [role="tree"], [role="grid"], ' +
        '[role="combobox"], [aria-activedescendant]';
    // Dropdown triggers (not menu or tree items, whose arrows open submenus or expand nodes)
    var DROPDOWN = '[role="combobox"], [aria-haspopup]:not([aria-haspopup="false"]):not([role="menuitem"]):not([role="treeitem"])';

    function ensureFocusStyle() {
        if (document.getElementById(STYLE_ID) || !document.head) return;
        var style = document.createElement('style');
        style.id = STYLE_ID;
        style.textContent = '.microg-dpad-focus { outline: 3px solid #8ab4f8 !important; outline-offset: 2px !important; }';
        document.head.appendChild(style);
    }

    function isTextField(el) {
        if (!el) return false;
        if (el.isContentEditable || el.tagName === 'TEXTAREA') return true;
        return el.tagName === 'INPUT' && TEXT_INPUT_TYPES.indexOf((el.getAttribute('type') || '').toLowerCase()) >= 0;
    }

    function isCandidate(el) {
        if (el.disabled || el.getAttribute('aria-disabled') === 'true') return false;
        var tabIndex = el.getAttribute('tabindex');
        if (tabIndex !== null && parseInt(tabIndex, 10) < 0) return false;
        // aria-hidden alone does not make an element non-interactive (e.g. reCAPTCHA image tiles)
        if (el.closest('[inert]')) return false;
        var rect = el.getBoundingClientRect();
        if (rect.width < 1 || rect.height < 1) return false;
        // Transparent elements stay candidates: Material checkboxes and radios are native inputs
        // with opacity 0 underneath their custom visuals.
        var style = window.getComputedStyle(el);
        return style.visibility !== 'hidden' && style.display !== 'none';
    }

    function isDeadFrame(el) {
        return el.__microgDpadDead && Date.now() - el.__microgDpadDead < DEAD_FRAME_MS;
    }

    // within: only descendants of that container; otherwise everything except the current
    // element, its descendants and the containers around it.
    function candidates(current, within) {
        var all = (within || document).querySelectorAll(FOCUSABLE);
        var result = [];
        for (var i = 0; i < all.length; i++) {
            var el = all[i];
            if (!within && (el === current || (current && (current.contains(el) || el.contains(current))))) continue;
            if (isDeadFrame(el)) continue;
            if (isCandidate(el)) result.push(el);
        }
        return result;
    }

    // An imaginary line at the edge of the given bounds that the search starts from.
    function edge(bounds, direction) {
        var from = {
            top: direction === 'up' ? bounds.bottom : bounds.top - 1,
            bottom: direction === 'up' ? bounds.bottom + 1 : bounds.top,
            left: direction === 'left' ? bounds.right : bounds.left - 1,
            right: direction === 'left' ? bounds.right + 1 : bounds.left
        };
        if (direction === 'up' || direction === 'down') { from.left = bounds.left; from.right = bounds.right; }
        else { from.top = bounds.top; from.bottom = bounds.bottom; }
        return from;
    }

    // Gap between two ranges on one axis, 0 if they overlap.
    function gap(aStart, aEnd, bStart, bEnd) {
        if (bEnd < aStart) return aStart - bEnd;
        if (bStart > aEnd) return bStart - aEnd;
        return 0;
    }

    function findNext(current, direction) {
        var from, list;
        if (current === document.body || current === document.documentElement) current = null;
        if (current && !current.matches(CONTROL)) {
            // A focused container (e.g. a dialog the page focused): enter it from its edge
            var inner = candidates(current, current);
            if (inner.length) { list = inner; from = edge(current.getBoundingClientRect(), direction); }
        }
        if (!list) {
            list = candidates(current);
            if (current) from = current.getBoundingClientRect();
            // Nothing focused yet: start from the edge of the viewport
            else from = edge({ top: 0, left: 0, bottom: window.innerHeight, right: window.innerWidth }, direction);
        }
        var fromCx = (from.left + from.right) / 2, fromCy = (from.top + from.bottom) / 2;
        var best = null, bestScore = Infinity;
        for (var i = 0; i < list.length; i++) {
            var r = list[i].getBoundingClientRect();
            var cx = (r.left + r.right) / 2, cy = (r.top + r.bottom) / 2;
            var primary, secondary;
            switch (direction) {
                case 'down':
                    if (cy <= fromCy + 1 || r.bottom <= from.bottom) continue;
                    primary = Math.max(0, r.top - from.bottom); secondary = gap(from.left, from.right, r.left, r.right); break;
                case 'up':
                    if (cy >= fromCy - 1 || r.top >= from.top) continue;
                    primary = Math.max(0, from.top - r.bottom); secondary = gap(from.left, from.right, r.left, r.right); break;
                case 'right':
                    if (cx <= fromCx + 1 || r.right <= from.right) continue;
                    primary = Math.max(0, r.left - from.right); secondary = gap(from.top, from.bottom, r.top, r.bottom); break;
                case 'left':
                    if (cx >= fromCx - 1 || r.left >= from.left) continue;
                    primary = Math.max(0, from.left - r.right); secondary = gap(from.top, from.bottom, r.top, r.bottom); break;
            }
            // Strongly prefer elements that are aligned with the current one. Like Android's
            // FocusFinder, anything in the same row always wins when moving left or right.
            var score = primary + secondary * 3;
            if (secondary > 0 && (direction === 'left' || direction === 'right')) score += 1e6;
            if (score < bestScore) { bestScore = score; best = list[i]; }
        }
        return best;
    }

    function clearFocusRing() {
        var rings = document.querySelectorAll('.microg-dpad-focus');
        for (var i = 0; i < rings.length; i++) rings[i].classList.remove('microg-dpad-focus');
    }

    // A transparent native input (Material checkbox/radio) cannot show an outline itself
    function ringTarget(el) {
        if (parseFloat(window.getComputedStyle(el).opacity) === 0 && el.parentElement) return el.parentElement;
        return el;
    }

    function moveFocus(el, direction) {
        clearFocusRing();
        ensureFocusStyle();
        if (el.tagName === 'IFRAME') {
            // Continue inside the frame (it may be cross-origin, so ask its copy of this script)
            var from = document.activeElement;
            el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
            el.focus({ preventScroll: true });
            if (el.contentWindow) el.contentWindow.postMessage({ type: MESSAGE, action: 'enter', direction: direction }, '*');
            el.__microgDpadPending = setTimeout(function () {
                el.__microgDpadDead = Date.now();
                if (document.activeElement !== el) return;
                if (from && from !== document.body && from !== el && from.isConnected) {
                    // An iframe is refocused as is: its document keeps its own focused element
                    if (from.tagName === 'IFRAME') from.focus({ preventScroll: true });
                    else moveFocus(from, direction);
                }
                if (document.activeElement === el) el.blur();
            }, 500);
            return;
        }
        el.focus({ preventScroll: true });
        if (document.activeElement !== el) {
            // Not focusable as is (e.g. a role="button" without tabindex)
            el.setAttribute('tabindex', '0');
            el.focus({ preventScroll: true });
        }
        if (!isTextField(el)) ringTarget(el).classList.add('microg-dpad-focus');
        el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    }

    // Nothing left in the pressed direction: hand over to the frame containing this document.
    function leaveFrame(direction) {
        if (window.parent === window) return false;
        window.parent.postMessage({ type: MESSAGE, action: 'leave', direction: direction }, '*');
        return true;
    }

    window.addEventListener('message', function (event) {
        var data = event.data;
        if (!data || data.type !== MESSAGE) return;
        if (data.action === 'enter' && event.source === window.parent) {
            window.parent.postMessage({ type: MESSAGE, action: 'entered' }, '*');
            var first = findNext(null, data.direction);
            if (first) moveFocus(first, data.direction);
            else leaveFrame(data.direction);
        } else if (data.action === 'entered') {
            var all = document.getElementsByTagName('iframe');
            for (var j = 0; j < all.length; j++) {
                if (all[j].contentWindow !== event.source) continue;
                clearTimeout(all[j].__microgDpadPending);
                all[j].__microgDpadDead = 0;
            }
        } else if (data.action === 'leave') {
            var frames = document.getElementsByTagName('iframe');
            for (var i = 0; i < frames.length; i++) {
                if (frames[i].contentWindow !== event.source) continue;
                var next = findNext(frames[i], data.direction);
                if (next) moveFocus(next, data.direction);
                else leaveFrame(data.direction);
                return;
            }
        }
    });

    document.addEventListener('focusout', clearFocusRing, true);

    document.addEventListener('keydown', function (event) {
        if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return;
        var active = document.activeElement;
        if (active && active.tagName === 'IFRAME') return;
        var direction = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' }[event.key];
        if (direction) {
            // Left/right move the caret inside text fields; selects keep their own arrow handling
            if (isTextField(active) && (direction === 'left' || direction === 'right')) return;
            // List boxes keep up/down for their options, except at the first/last one
            if (active && active.tagName === 'SELECT' && (active.multiple || active.size > 1) &&
                ((direction === 'up' && active.selectedIndex > 0) ||
                 (direction === 'down' && active.selectedIndex < active.options.length - 1))) return;
            var widget = active && active.closest && active.closest(COMPOSITE);
            // A collapsed dropdown is a plain control: arrows move on, OK opens it. Otherwise
            // dropdowns that open on arrow keys could never be left with a D-pad.
            var dropdown = active && active.closest && active.closest(DROPDOWN);
            if (dropdown && dropdown.getAttribute('aria-expanded') !== 'true') widget = null;
            if (widget) {
                // Let the widget handle the key first; only move on if it did nothing with it
                var descendant = widget.getAttribute('aria-activedescendant');
                setTimeout(function () {
                    if (event.defaultPrevented || document.activeElement !== active ||
                        widget.getAttribute('aria-activedescendant') !== descendant) return;
                    var next = findNext(active, direction);
                    if (next) moveFocus(next, direction); else leaveFrame(direction);
                }, 0);
                return;
            }
            var next = findNext(active, direction);
            if (!next) {
                if (leaveFrame(direction)) event.preventDefault();
                return; // otherwise fall back to scrolling
            }
            event.preventDefault();
            event.stopPropagation();
            moveFocus(next, direction);
        } else if (event.key === 'Enter' && active && active.tagName === 'INPUT' &&
            (active.type === 'checkbox' || active.type === 'radio')) {
            event.preventDefault();
            active.click();
        }
    }, true);
})();
