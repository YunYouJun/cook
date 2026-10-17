"""Verify classic / matching views and persisted settings on a touch viewport.

python scripts/qa/recipe-views-mobile.py http://127.0.0.1:3101
Requires Python Playwright and Google Chrome.
"""
from datetime import datetime
import json
from pathlib import Path
import sys
from zoneinfo import ZoneInfo

from playwright.sync_api import expect, sync_playwright
from recipe_view_helpers import choose_home_view, expect_home_view

BASE_URL = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:3101'
OUTPUT = Path(__file__).resolve().parents[2] / 'artifacts' / 'recommendations'
OUTPUT.mkdir(parents=True, exist_ok=True)
report = {'tested_at': datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(timespec='seconds'), 'checks': [], 'errors': []}


def screenshot(page, name):
    page.wait_for_timeout(300)
    page.screenshot(path=str(OUTPUT / name))


with sync_playwright() as p:
    browser = p.chromium.launch(channel='chrome', headless=True)
    report['browser'] = browser.version
    context = browser.new_context(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True, device_scale_factor=1)
    page = context.new_page()
    page.on('pageerror', lambda error: report['errors'].append(str(error)))
    page.goto(BASE_URL)
    page.wait_for_load_state('networkidle')
    expect_home_view(page, '经典视图')
    expect(page.locator('.ingredient-basket')).to_have_count(0)
    expect(page.locator('.pantry-settings')).to_have_count(0)
    expect(page.get_by_role('button', name='模糊匹配', exact=True)).to_be_visible()
    expect(page.get_by_role('button', name='打开食材篮操作', exact=True)).to_be_visible()
    screenshot(page, 'mobile-classic.png')

    picker = page.locator('ion-select.recipe-view-menu:visible')
    picker_box = picker.bounding_box()
    title_box = page.locator('.home-reset:visible').bounding_box()
    assert picker_box['width'] >= 44 and picker_box['height'] >= 44, picker_box
    assert title_box['x'] + title_box['width'] <= picker_box['x'], (title_box, picker_box)
    assert page.get_by_role('heading', name='🥘 先选一下食材').bounding_box()['y'] < 100
    expect(page.locator('.choose-food .recipe-view-switch')).to_have_count(0)
    picker.tap()
    expect(page.get_by_role('radio', name='经典视图', exact=True)).to_be_checked()
    expect(page.locator('ion-popover')).to_contain_text('选择会自动记住')
    screenshot(page, 'mobile-view-menu.png')
    page.touchscreen.tap(8, 350)
    expect(page.locator('ion-popover')).to_have_count(0)
    expect_home_view(page, '经典视图')
    picker_button = page.get_by_role('button', name='做菜视图, 经典视图', exact=True)
    expect(picker_button).to_be_focused()
    page.keyboard.press('Enter')
    expect(page.get_by_role('radio', name='经典视图', exact=True)).to_be_visible()
    page.keyboard.press('Escape')
    expect(page.locator('ion-popover')).to_have_count(0)
    expect(picker_button).to_be_focused()
    report['checks'].append({'compact_toolbar_picker': picker_box, 'checks': 'No added content row; selected option and hint; tap outside / Escape close; focus restored; Enter opens'})

    # Classic still allows kitchen-tool-only search.
    page.get_by_role('button', name='电饭煲', exact=True).tap()
    expect(page.locator('.classic-recipe-panel .dish-tag').first).to_be_visible()
    tool_only_count = page.locator('.classic-recipe-panel .dish-tag').count()
    assert tool_only_count > 0
    page.get_by_role('button', name='电饭煲', exact=True).tap()
    page.locator('button.vegetable-tag').filter(has_text='土豆').tap()
    expect(page.locator('.classic-recipe-panel .dish-tag').first).to_be_visible()
    classic_count = page.locator('.classic-recipe-panel .dish-tag').count()
    assert classic_count > 79  # Matching view excludes more than two missing ingredients.
    page.get_by_role('button', name='打开食材篮操作', exact=True).tap()
    page.get_by_role('button', name='查看经典菜谱', exact=True).tap()
    page.locator('.classic-recipe-panel').evaluate("el => el.scrollIntoView({block: 'start'})")
    screenshot(page, 'mobile-classic-results.png')
    report['checks'].append({'classic_tool_only_count': tool_only_count, 'classic_potato_count': classic_count})

    choose_home_view(page, '匹配视图')
    expect(page.get_by_role('button', name='移除土豆', exact=True)).to_be_visible()
    expect(page.get_by_role('button', name='食材齐全 30 道', exact=True)).to_be_visible()
    page.reload()
    page.wait_for_load_state('networkidle')
    expect_home_view(page, '匹配视图')
    expect(page.get_by_role('button', name='移除土豆', exact=True)).to_be_visible()
    report['checks'].append('Home switch retains selection and remembers matching view after reload')

    # Change the preference via the actual settings route.
    page.goto(BASE_URL + '/settings')
    page.wait_for_load_state('networkidle')
    expect(page.get_by_role('button', name='匹配视图', exact=True)).to_have_attribute('aria-pressed', 'true')
    page.get_by_role('button', name='经典视图', exact=True).tap()
    screenshot(page, 'mobile-view-settings.png')
    page.goto(BASE_URL)
    page.wait_for_load_state('networkidle')
    expect_home_view(page, '经典视图')
    expect(page.locator('button.vegetable-tag').filter(has_text='土豆')).to_have_attribute('aria-pressed', 'true')
    page.reload()
    page.wait_for_load_state('networkidle')
    expect_home_view(page, '经典视图')
    report['checks'].append('Settings changes home view; classic view and ingredients survive reload')

    # Also test Ionic route caching: navigation back must update the already-mounted home.
    page.get_by_role('tab', name='我的', exact=True).tap()
    page.get_by_text('设置', exact=True).tap()
    page.get_by_role('button', name='匹配视图', exact=True).tap()
    page.locator('ion-back-button').tap()
    page.get_by_role('tab', name='做菜', exact=True).tap()
    expect_home_view(page, '匹配视图')
    expect(page.get_by_role('button', name='移除土豆', exact=True)).to_be_visible()
    report['checks'].append('Settings selection also updates the cached Ionic home via back/tab navigation')

    page.set_viewport_size({'width': 320, 'height': 740})
    choose_home_view(page, '经典视图')
    assert page.evaluate('document.body.scrollWidth <= innerWidth')
    assert page.locator('ion-title:visible').evaluate("e => { const title = e.shadowRoot.querySelector('.toolbar-title'); return title.scrollWidth <= title.clientWidth }")
    title_box = page.locator('.home-reset:visible').bounding_box()
    picker_box = page.locator('ion-select.recipe-view-menu:visible').bounding_box()
    assert title_box['x'] + title_box['width'] <= picker_box['x'], (title_box, picker_box)
    screenshot(page, 'mobile-classic-320.png')
    page.locator('ion-select.recipe-view-menu:visible').tap()
    expect(page.get_by_role('radio', name='匹配视图', exact=True)).to_be_visible()
    screenshot(page, 'mobile-view-menu-320.png')
    menu_box = page.locator('ion-select-popover').bounding_box()
    assert 0 <= menu_box['x'] and menu_box['x'] + menu_box['width'] <= 320, menu_box
    page.keyboard.press('Escape')
    report['checks'].append('Classic layout and switch fit a 320 px viewport')
    context.close()

    # Emulate an installed user's old settings: absence of recipeView must preserve classic.
    legacy = browser.new_context(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True)
    legacy.add_init_script("if (!localStorage.getItem('cook:settings')) localStorage.setItem('cook:settings', JSON.stringify({keepLocalData: false}))")
    old_page = legacy.new_page()
    old_page.on('pageerror', lambda error: report['errors'].append(str(error)))
    old_page.goto(BASE_URL)
    old_page.wait_for_load_state('networkidle')
    expect_home_view(old_page, '经典视图')
    choose_home_view(old_page, '匹配视图')
    old_page.locator('button.vegetable-tag').filter(has_text='番茄').tap()
    old_page.reload()
    old_page.wait_for_load_state('networkidle')
    expect_home_view(old_page, '匹配视图')
    expect(old_page.locator('.basket-item')).to_have_count(0)
    assert old_page.evaluate("JSON.parse(localStorage.getItem('cook:settings')).keepLocalData") is False
    report['checks'].append('Old settings migrate to classic; disabling ingredient persistence still remembers the chosen view')
    legacy.close()
    browser.close()

assert not report['errors'], report['errors']
report['passed'] = True
(OUTPUT / 'view-validation.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(report, ensure_ascii=False, indent=2))
