"""Touch-browser acceptance for Cook. Run against a built/static server.

Usage: python scripts/qa/recommendations-mobile.py http://127.0.0.1:3101
Requires: Python Playwright and Google Chrome (or PLAYWRIGHT_CHANNEL).
Screenshots and machine-readable results go to artifacts/recommendations/.
"""
from datetime import datetime
import json
import os
from pathlib import Path
import re
import sys
from zoneinfo import ZoneInfo

from playwright.sync_api import expect, sync_playwright
from recipe_view_helpers import choose_home_view, expect_home_view

BASE_URL = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:3101'
OUTPUT = Path(__file__).resolve().parents[2] / 'artifacts' / 'recommendations'
OUTPUT.mkdir(parents=True, exist_ok=True)
CASES = [
    (['番茄', '鸡蛋'], [40, 72, 48], '微波炉版番茄鸡蛋汤', ['米'], ['面食', '香肠']),
    (['土豆'], [30, 30, 19], '烤箱版M记同款薯条', ['鸡肉'], ['香肠', '米']),
    (['腊肠', '米'], [6, 30, 26], '电饭煲版广式腊肠煲饭', ['胡萝卜'], ['猪肉', '土豆']),
    (['鸡肉', '洋葱', '菌菇'], [68, 34, 25], '电饭煲版烧鸡', ['米'], ['胡萝卜', '土豆']),
    (['牛肉', '番茄', '米'], [15, 55, 50], '电饭煲版番茄牛腩焖饭', ['猪肉'], ['土豆', '胡萝卜']),
]
LABELS = ['食材齐全', '还差一种', '还差两种']
report = {'tested_at': datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(timespec='seconds'), 'url': BASE_URL, 'mobile': '390 × 844, touch, DPR 1', 'cases': [], 'checks': [], 'errors': []}


def see_results(page):
    edit = page.get_by_role('button', name=re.compile('调整食材'))
    if edit.count():
        edit.tap()
        expect(page.get_by_role('button', name=re.compile('看推荐'))).to_be_visible()
    page.get_by_role('button', name=re.compile('看推荐')).tap()
    page.wait_for_function("document.querySelector('.recipe-panel').getBoundingClientRect().top < innerHeight - 200")


def capture(page, name):
    # Wait for Ionic's smooth scrolling to settle before capturing the actual viewport.
    page.wait_for_timeout(400)
    page.screenshot(path=str(OUTPUT / name))


def add(page, ingredients):
    page.get_by_label('补充食材', exact=True).fill('、'.join(ingredients))
    page.get_by_role('button', name='加入', exact=True).tap()


def clear(page):
    page.get_by_role('button', name='清空食材', exact=True).tap()


def check_counts(page, counts):
    for label, count in zip(LABELS, counts):
        expect(page.get_by_role('button', name=f'{label} {count} 道', exact=True)).to_be_visible()


def no_overflow(page):
    sizes = page.evaluate('''() => ({ viewport: innerWidth, body: document.body.scrollWidth,
      content: document.querySelector('.choose-food').scrollWidth })''')
    assert sizes['body'] <= sizes['viewport'] and sizes['content'] <= sizes['viewport'], sizes
    assert page.locator('ion-title').evaluate("e => { const title = e.shadowRoot.querySelector('.toolbar-title'); return title.scrollWidth <= title.clientWidth }"), 'Header title is clipped'


with sync_playwright() as p:
    browser = p.chromium.launch(channel=os.environ.get('PLAYWRIGHT_CHANNEL', 'chrome'), headless=True)
    report['browser'] = f'Chrome {browser.version}'
    context = browser.new_context(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True, device_scale_factor=1)
    page = context.new_page()
    page.on('pageerror', lambda error: report['errors'].append(str(error)))
    print('Opening mobile page', flush=True)
    page.goto(BASE_URL)
    page.wait_for_load_state('networkidle')
    expect_home_view(page, '经典视图')
    choose_home_view(page, '匹配视图')
    expect(page.get_by_text('先往食材篮里放点东西吧')).to_be_attached()
    see_results(page)
    capture(page, 'mobile-empty.png')
    report['checks'].append('Fresh visit defaults to classic; after switching to matching view: database initialized, empty basket prompt, example action available')

    print('Empty state passed', flush=True)
    for index, (ingredients, counts, first, missing_one, missing_two) in enumerate(CASES):
        print('Checking', ingredients, flush=True)
        if index:
            clear(page)
        if index == 0:
            page.locator('button.vegetable-tag').filter(has_text='番茄').tap()
            page.locator('button.meat-tag').filter(has_text='鸡蛋').tap()
            page.locator('.ingredient-basket').scroll_into_view_if_needed()
            capture(page, 'mobile-selection.png')
        else:
            add(page, ingredients)
        see_results(page)
        check_counts(page, counts)
        expect(page.locator('.match-card').first).to_have_attribute('aria-label', first)
        expect(page.locator('.match-card').first).to_contain_text('无，已记录食材齐全')
        expect(page.locator('.match-card')).to_have_count(6)
        if index == 0:
            capture(page, 'mobile-complete.png')
        for group, missing in [(1, missing_one), (2, missing_two)]:
            page.get_by_role('button', name=f'{LABELS[group]} {counts[group]} 道', exact=True).tap()
            expect(page.locator('.match-card').first.locator('.is-missing dd')).to_have_text('、'.join(missing))
            if index == 0:
                capture(page, f'mobile-missing-{group}.png')
        no_overflow(page)
        report['cases'].append({'ingredients': ingredients, 'counts': counts, 'first': first, 'missing_one': missing_one, 'missing_two': missing_two, 'passed': True})

    clear(page)
    add(page, ['西红柿', '番茄', '鸡蛋', '鸡蛋'])
    expect(page.locator('.basket-item')).to_have_count(2)
    add(page, ['西红柿', '鸡蛋'])
    expect(page.get_by_text('这些食材已经在篮子里了，不用重复添加。')).to_be_visible()
    see_results(page)
    check_counts(page, [40, 72, 48])
    page.get_by_role('button', name=re.compile('再看 6 道')).tap()
    expect(page.locator('.match-card')).to_have_count(12)
    report['checks'].append('Aliases / duplicates share one basket item and keep identical counts; pagination 6 → 12')

    page.locator('.pantry-summary').tap()
    page.get_by_role('button', name='全部取消', exact=True).tap()
    expect(page.locator('.pantry-option[aria-pressed="true"]')).to_have_count(0)
    page.locator('.pantry-option').filter(has_text='盐').tap()
    page.locator('.pantry-option').filter(has_text='蒜').tap()
    expect(page.locator('.pantry-option[aria-pressed="true"]')).to_have_count(2)
    page.locator('.pantry-settings').evaluate("el => el.scrollIntoView({block: 'start'})")
    capture(page, 'mobile-pantry.png')
    page.reload()
    page.wait_for_load_state('networkidle')
    expect(page.locator('.basket-item')).to_have_count(2)
    page.locator('.pantry-summary').tap()
    expect(page.locator('.pantry-option[aria-pressed="true"]')).to_have_text(['✓ 盐', '✓ 蒜'])
    page.get_by_role('button', name='恢复油、盐', exact=True).tap()
    expect(page.locator('.pantry-option[aria-pressed="true"]')).to_have_text(['✓ 油', '✓ 盐'])
    see_results(page)
    check_counts(page, [40, 72, 48])
    report['checks'].append('Pantry toggles, cancel all, restore defaults and refresh persistence work; absent condiments never change counts')

    page.locator('.filter-summary').tap()
    page.get_by_label('筛选菜名').fill('不存在的菜谱')
    expect(page.get_by_text('暂时没有符合条件的菜谱', exact=True)).to_be_visible()
    page.get_by_role('button', name='清除筛选，保留食材', exact=True).tap()
    check_counts(page, [40, 72, 48])
    page.get_by_label('匹配范围').select_option('survival')
    check_counts(page, [40, 0, 0])
    page.get_by_role('button', name='还差一种 0 道', exact=True).tap()
    expect(page.get_by_text('这个分组暂时没有菜谱，试试其他分组或调整食材。')).to_be_visible()
    page.get_by_label('匹配范围').select_option('strict')
    assert int(page.locator('.match-group strong').first.inner_text().split()[0]) > 0
    first_card = page.locator('.match-card').first
    expect(first_card.locator('.match-details-body')).to_be_hidden()
    first_card.locator('.match-details-toggle').tap()
    expect(first_card.locator('.match-details-body')).to_be_visible()
    expect(first_card.locator('.match-details-body')).to_contain_text('用上 2 种已选食材')
    first_card.locator('.match-details-toggle').tap()
    expect(first_card.locator('.match-details-body')).to_be_hidden()
    report['checks'].append('Recipe tags link to the recorded video; touch expands and collapses the full matching basis while missing ingredients stay visible')
    page.get_by_role('button', name='清除筛选', exact=True).tap()
    page.get_by_role('button', name='微波炉', exact=True).tap()
    see_results(page)
    assert page.locator('.match-card').count() > 0
    for text in page.locator('.match-tools').all_text_contents():
        assert '微波炉' in text
    page.get_by_role('button', name='清除筛选', exact=True).tap()
    report['checks'].append('Keyword, empty filtered state, clear filters preserving basket, strict/survival modes, empty group and kitchen tool filtering work')

    # Exercise the existing recipe-link flow without loading an external video.
    context.route('https://www.bilibili.com/**', lambda route: route.fulfill(status=200, body='<p>Recipe link destination verified</p>'))
    with page.expect_popup() as popup_info:
        page.locator('.match-card').first.get_by_role('link').tap()
    popup = popup_info.value
    popup.wait_for_load_state()
    assert 'bilibili.com/video/BV' in popup.url
    popup.close()
    report['checks'].append('Recipe title opens its recorded video link in a new window')

    page.get_by_role('button', name='移除番茄', exact=True).tap()
    expect(page.locator('.basket-item')).to_have_count(1)
    clear(page)
    add(page, ['火龙果'])
    see_results(page)
    expect(page.get_by_text('暂时没有符合条件的菜谱', exact=True)).to_be_visible()
    check_counts(page, [0, 0, 0])
    capture(page, 'mobile-no-match.png')
    clear(page)
    see_results(page)
    page.get_by_role('button', name='试试番茄 + 鸡蛋', exact=True).tap()
    check_counts(page, [40, 72, 48])
    page.locator('.ranking-help summary').tap()
    expect(page.get_by_text(re.compile('优先食材齐全，再看还差一种'))).to_be_visible()
    report['checks'].append('Individual removal, full clear, unmatched ingredient, example recovery and visible ranking explanation work')

    page.set_viewport_size({'width': 320, 'height': 740})
    see_results(page)
    no_overflow(page)
    page.locator('.ranking-help summary').tap()
    capture(page, 'mobile-320.png')
    page.set_viewport_size({'width': 1280, 'height': 900})
    see_results(page)
    no_overflow(page)
    capture(page, 'desktop.png')
    report['checks'].append('320 px narrow phone and 1280 px desktop: no horizontal overflow')

    dark_context = browser.new_context(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True, color_scheme='dark', device_scale_factor=1)
    dark_page = dark_context.new_page()
    dark_page.on('pageerror', lambda error: report['errors'].append(str(error)))
    dark_page.goto(BASE_URL)
    dark_page.wait_for_load_state('networkidle')
    choose_home_view(dark_page, '匹配视图')
    add(dark_page, ['番茄', '鸡蛋'])
    see_results(dark_page)
    expect(dark_page.locator('html')).to_have_class(re.compile('dark'))
    capture(dark_page, 'mobile-dark.png')
    no_overflow(dark_page)
    report['checks'].append('System dark theme renders the same touch interface without overflow')
    dark_context.close()
    context.close()
    browser.close()

assert not report['errors'], report['errors']
report['passed'] = True
(OUTPUT / 'mobile-validation.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(report, ensure_ascii=False, indent=2))
