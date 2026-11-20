import assert from 'node:assert/strict';
import test from 'node:test';
import { CUSTOMER_NAV_GROUPS, createCustomerNavigationGroups, getNavigationState, getNavigationPresentation, isNavigationItemActive, transitionNavigationOverlay, isNavigationGroupActive, isNavigationItemAvailable } from './customerNavigation.js';

const fullFeatures = { aiStudio: true, cart: true, checkout: true, printQuote: true };

test('customer navigation defaults to only routes that are enabled while keeping guides available', () => {
    const groups = createCustomerNavigationGroups({});
    assert.deepEqual(groups.map(group => group.id), ['store', 'models', 'guides', 'about']);
    assert.ok(groups.flatMap(group => group.items).every(item => !/^\/(?:studio|cart|checkout|print-quote)(?:\?|$)/.test(item.to || '')));
    assert.deepEqual(groups.find(group => group.id === 'guides').items.map(item => item.to), ['/workflow', '/resources']);
});

test('store navigation links only to the public catalog without unsupported filters', () => {
    const store = CUSTOMER_NAV_GROUPS.find(group => group.id === 'store');
    assert.deepEqual(store.items.map(item => item.to), ['/marketplace']);
    assert.doesNotMatch(JSON.stringify(store), /collection=/);
});

test('full feature navigation restores AI Studio, printing, cart, and checkout destinations', () => {
    const groups = createCustomerNavigationGroups(fullFeatures);
    const items = groups.flatMap(group => group.items);
    for (const route of ['/studio?mode=image', '/cart', '/checkout', '/print-quote']) {
        assert.ok(items.some(item => item.to === route), `${route} is discoverable`);
    }
    assert.ok(items.some(item => item.label === 'Thanh toán' && item.disabled && !item.to));
});

test('unready navigation items have no active destination', () => {
    const items = createCustomerNavigationGroups(fullFeatures).flatMap(group => group.items);
    assert.ok(items.filter(item => item.disabled).every(item => !item.to));
    const quote = items.find(item => item.requiresPrintDraft);
    assert.ok(quote && !isNavigationItemAvailable(quote, { state: {} }));
    assert.ok(isNavigationItemAvailable(quote, { state: { printDraft: { modelId: 4 } } }));
    assert.ok(items.some(item => item.to === '/resources'));
});

test('navigation preserves existing editor drafts only for their matching routes', () => {
    const modelEditDraft = { modelEditDraft: { prompt: 'keep this draft' } };
    const printDraft = { printDraft: { modelId: 7 } };
    assert.equal(getNavigationState('/studio?mode=image', modelEditDraft), modelEditDraft);
    assert.equal(getNavigationState('/print-quote', printDraft), printDraft);
    assert.equal(getNavigationState('/marketplace', modelEditDraft), undefined);
});

test('route groups have one active navigation group at a time', () => {
    for (const pathname of ['/marketplace', '/product/42', '/studio', '/products', '/workflow', '/print-quote', '/about', '/cart']) {
        const active = CUSTOMER_NAV_GROUPS.filter(group => isNavigationGroupActive(group.id, pathname));
        assert.ok(active.length <= 1, `${pathname} activates ${active.map(group => group.id).join(', ')}`);
    }
    assert.equal(CUSTOMER_NAV_GROUPS.find(group => group.id === 'store' && isNavigationGroupActive(group.id, '/product/42'))?.id, 'store');
    assert.equal(CUSTOMER_NAV_GROUPS.find(group => group.id === 'about' && isNavigationGroupActive(group.id, '/about'))?.id, 'about');
});

test('desktop and compact layouts render only their own navigation menu', () => {
    assert.deepEqual(getNavigationPresentation({ compact: false, mobileOpen: true, openGroup: 'store' }), {
        showDesktopMegaMenu: true, showMobileMenu: false, visibleGroup: 'store',
    });
    assert.deepEqual(getNavigationPresentation({ compact: true, mobileOpen: true, openGroup: 'store' }), {
        showDesktopMegaMenu: false, showMobileMenu: true, visibleGroup: 'store',
    });
    assert.deepEqual(getNavigationPresentation({ compact: true, mobileOpen: false, openGroup: 'store' }), {
        showDesktopMegaMenu: false, showMobileMenu: false, visibleGroup: null,
    });
});

test('opening one overlay closes conflicting overlays and closing mobile clears its accordion', () => {
    const initial = { mobileOpen: false, openGroup: 'store', searchOpen: true, accountOpen: true };
    assert.deepEqual(transitionNavigationOverlay(initial, { type: 'open-group', groupId: 'ai' }), {
        mobileOpen: false, openGroup: 'ai', searchOpen: false, accountOpen: false,
    });
    assert.deepEqual(transitionNavigationOverlay({ ...initial, mobileOpen: true, openGroup: 'store' }, { type: 'toggle-mobile' }), {
        mobileOpen: false, openGroup: null, searchOpen: false, accountOpen: false,
    });
    assert.deepEqual(transitionNavigationOverlay(initial, { type: 'open-search' }), {
        mobileOpen: false, openGroup: null, searchOpen: true, accountOpen: false,
    });
    assert.deepEqual(transitionNavigationOverlay(initial, { type: 'open-account' }), {
        mobileOpen: false, openGroup: null, searchOpen: false, accountOpen: true,
    });
});

test('storefront navigation and about anchors have exact active states', () => {
    const store = CUSTOMER_NAV_GROUPS.find(group => group.id === 'store').items[0];
    assert.equal(isNavigationItemActive(store, { pathname: '/marketplace', search: '?search=key', hash: '' }), true);
    const about = CUSTOMER_NAV_GROUPS.find(group => group.id === 'about').items[1];
    assert.equal(isNavigationItemActive(about, { pathname: '/about', search: '', hash: '#pocket-joy' }), true);
    assert.equal(isNavigationItemActive(about, { pathname: '/about', search: '', hash: '#quy-trinh' }), false);
});

test('full feature navigation groups have valid rich-menu actions and route-backed CTAs', () => {
    const groups = createCustomerNavigationGroups(fullFeatures);
    assert.equal(groups.length, 7);
    assert.equal(new Set(groups.map(group => group.richMenu?.title)).size, groups.length);
    for (const group of groups) {
        const menu = group.richMenu;
        assert.ok(menu, `${group.id} has rich menu content`);
        assert.ok(menu.title && menu.description && menu.icon);
        assert.ok(group.items[menu.ctaIndex]?.to, `${group.id} CTA points to an existing route`);
        assert.ok(Array.isArray(menu.actions) && menu.actions.length > 0);
        assert.ok(menu.actions.every(action => group.items[action.itemIndex] && action.icon && action.description));
        assert.ok(menu.discovery?.title && menu.discovery?.description && menu.discovery?.kind);
    }
    const store = CUSTOMER_NAV_GROUPS.find(group => group.id === 'store');
    assert.deepEqual(store.items.map(item => item.to), ['/marketplace']);
    assert.deepEqual(store.richMenu.actions.map(action => store.items[action.itemIndex].to), ['/marketplace']);
    assert.doesNotMatch(JSON.stringify(store), /collection=/);
});

test('restored menu content keeps verified empty states and marks payment as unavailable', () => {
    const groups = createCustomerNavigationGroups(fullFeatures);
    const catalogMenu = groups.find(group => group.id === 'models').richMenu.discovery;
    assert.match(catalogMenu.title, /Chưa có mô hình công khai được duyệt/);
    assert.match(JSON.stringify(groups), /AI Studio|\/studio/);
    assert.match(JSON.stringify(groups), /Thanh toán trực tuyến chưa được hỗ trợ/);
    assert.doesNotMatch(JSON.stringify(groups), /tạo ảnh 2D|generate 2D/i);
});
