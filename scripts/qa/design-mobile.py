"""Validate scoped design integration; optionally compare a pre-migration baseline.

python scripts/qa/design-mobile.py http://127.0.0.1:3101 /tmp/cook-design-before
Requires Python Playwright and Google Chrome. The baseline contains styles.json
and classic-{width}-{theme}.png, captured from the previous production build.
"""
from datetime import datetime
import json
from pathlib import Path
import re
import sys
from zoneinfo import ZoneInfo

from playwright.sync_api import expect, sync_playwright
from recipe_view_helpers import choose_home_view, expect_home_view

BASE_URL = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:3101'
BASELINE = Path(sys.argv[2]) if len(sys.argv) > 2 else None
OUTPUT = Path(__file__).resolve().parents[2] / 'artifacts' / 'recommendations' / 'design'
OUTPUT.mkdir(parents=True, exist_ok=True)
report = {'tested_at': datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(timespec='seconds'), 'cases': [], 'errors': []}
styles = json.loads((BASELINE / 'styles.json').read_text()) if BASELINE else {}


def luminance(color):
    channels = [float(value) / 255 for value in re.findall(r'[\d.]+', color)[:3]]
    linear = [c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4 for c in channels]
    return sum(c * weight for c, weight in zip(linear, [0.2126, 0.7152, 0.0722]))


def primary_contrast(page):
    colors = page.locator('.add-button').evaluate('e => { const s = getComputedStyle(e); return [s.color, s.backgroundColor] }')
    light, dark = sorted(map(luminance, colors), reverse=True)
    ratio = (light + 0.05) / (dark + 0.05)
    assert ratio >= 4.5, (colors, ratio)
    return round(ratio, 2)


with sync_playwright() as p:
    browser = p.chromium.launch(channel='chrome', headless=True)
    report['browser'] = browser.version
    for width, theme in [(390, 'light'), (320, 'light'), (390, 'dark')]:
        context = browser.new_context(viewport={'width': width, 'height': 844}, is_mobile=True, has_touch=True, device_scale_factor=1, color_scheme=theme)
        page = context.new_page()
        page.on('pageerror', lambda error: report['errors'].append(str(error)))
        page.goto(BASE_URL)
        page.wait_for_load_state('networkidle')
        expect_home_view(page, '经典视图')
        expect(page.get_by_role('button', name='模糊匹配', exact=True)).to_be_visible()
        assert page.locator('.choose-food').evaluate("e => getComputedStyle(e).getPropertyValue('--ylf-font-body')") == ''
        page.wait_for_timeout(300)
        key = f'classic-{width}-{theme}'
        classic_png = page.screenshot(path=str(OUTPUT / f'{key}.png'))
        current_styles = page.locator('.choose-food').evaluate("e => [e,...e.querySelectorAll('button,h2')].map(n=>{let s=getComputedStyle(n),r=n.getBoundingClientRect();return {text:n.tagName==='BUTTON'?n.textContent.trim():n.tagName,rect:[r.x,r.y,r.width,r.height],color:s.color,background:s.backgroundColor,font:s.fontSize,border:s.border,radius:s.borderRadius}})")
        case = {'width': width, 'theme': theme}
        if BASELINE:
            assert current_styles == styles[key], f'{key}: classic computed styles changed'
            assert classic_png == (BASELINE / f'{key}.png').read_bytes(), f'{key}: classic screenshot changed'
            case['classic_baseline'] = 'Identical screenshot and computed styles'

        choose_home_view(page, '匹配视图')
        expect(page.locator('.ingredient-basket.ylf-card')).to_be_visible()
        expect(page.locator('.add-button.ylf-button')).to_be_visible()
        expect(page.locator('.basket-count.ylf-badge')).to_be_visible()
        assert page.locator('.choose-food').evaluate("e => getComputedStyle(e).getPropertyValue('--ylf-space-4').trim()") == '16px'
        assert page.locator('html').evaluate("e => getComputedStyle(e).getPropertyValue('--ylf-font-body')") == ''

        # Both keyboard form submission and the shared button must preserve behavior.
        field = page.get_by_label('补充食材', exact=True)
        field.fill('番茄、鸡蛋')
        field.press('Enter')
        expect(page.get_by_role('button', name='移除番茄', exact=True)).to_be_visible()
        page.wait_for_timeout(300)
        page.screenshot(path=str(OUTPUT / f'matching-{width}-{theme}.png'))
        case['basket_height'] = page.locator('.ingredient-basket').bounding_box()['height']
        long_name = 'abcdefghijklmnopqrstuvwxyz0123456789' * 2
        field.fill(long_name)
        page.get_by_role('button', name='加入', exact=True).tap()
        long_chip = page.get_by_role('button', name=f'移除{long_name}', exact=True)
        expect(long_chip).to_be_visible()
        box = long_chip.bounding_box()
        assert 0 <= box['x'] and box['x'] + box['width'] <= width, box
        assert page.locator('.ingredient-basket').evaluate('e => e.scrollWidth <= e.clientWidth')
        long_chip.tap()
        page.locator('.ingredient-basket').scroll_into_view_if_needed()
        page.wait_for_timeout(300)
        case['primary_text_contrast'] = primary_contrast(page)

        # Inspect the reading hierarchy independently of CSS token names.
        type_sizes = {
            '.basket-input': 16, '.basket-count': 12, '.basket-message': 12,
            '.basket-item': 14, '.add-button': 14, '.vegetable-tag': 14,
            '.pantry-count': 12, '.pantry-option': 14, '.pantry-description': 12,
            '.panel-note': 12, '.ranking-help summary': 14,
            '.keyword-input': 16, '.mode-select': 16,
            '.match-name': 16, '.match-tools': 12, '.match-evidence': 12,
            '.match-ingredients': 14, '.match-group': 14, '.match-group strong': 12,
            '.results-count': 12, '.recipe-count': 12,
        }
        case['type_sizes'] = {}
        for selector, expected in type_sizes.items():
            actual = page.locator(selector).first.evaluate('e => parseFloat(getComputedStyle(e).fontSize)')
            assert actual == expected, (selector, actual, expected)
            case['type_sizes'][selector] = actual

        # Check real rendered touch targets, including reused food tags and the FAB.
        for button in page.locator('.cook-match-theme button:visible').all():
            box = button.bounding_box()
            assert box['height'] >= 44 and box['width'] >= 44, (button.inner_text(), box)
        assert page.evaluate('document.body.scrollWidth <= innerWidth')
        page.get_by_role('button', name=re.compile('看推荐')).tap()
        page.wait_for_function("document.querySelector('.recipe-panel').getBoundingClientRect().top < 100")
        expect(page.get_by_role('button', name='食材齐全 40 道', exact=True)).to_be_visible()
        page.get_by_role('button', name='还差一种 71 道', exact=True).tap()
        expect(page.locator('.match-card').first.locator('.is-missing dd')).to_have_text('米')
        page.wait_for_timeout(400)
        page.screenshot(path=str(OUTPUT / f'results-{width}-{theme}.png'))
        case['group_height'] = page.locator('.match-groups').bounding_box()['height']
        case['first_recipe_height'] = page.locator('.match-card').first.bounding_box()['height']
        case['panel_height'] = page.locator('.recipe-panel').bounding_box()['height']
        for link in page.locator('.match-name .dish-tag').all():
            assert link.bounding_box()['height'] >= 44
        first = page.locator('.match-card').first
        expect(first.locator('.match-name .dish-tag')).to_contain_text('🍅')
        expect(first.locator('.match-name .dish-tag')).to_contain_text('🥚')
        toggle = first.locator('.match-details-toggle')
        body = first.locator('.match-details-body')
        expect(toggle).to_have_attribute('aria-expanded', 'false')
        expect(body).to_be_hidden()
        expect(first.locator('.match-evidence')).to_be_visible()
        expect(first.locator('.match-evidence')).to_have_text('用上 2 种')
        expect(first.locator('.is-missing')).to_be_visible()
        assert toggle.bounding_box()['height'] >= 44
        toggle.tap()
        expect(toggle).to_have_attribute('aria-expanded', 'true')
        expect(body).to_be_visible()
        expect(body).to_contain_text('用上 2 种已选食材，共记录 3 种')
        expect(body.locator('.match-ingredients')).to_contain_text('番茄、鸡蛋')
        case['expanded_recipe_height'] = first.bounding_box()['height']
        page.screenshot(path=str(OUTPUT / f'results-expanded-{width}-{theme}.png'))
        toggle.tap()
        expect(toggle).to_have_attribute('aria-expanded', 'false')
        expect(body).to_be_hidden()
        toggle.focus()
        toggle.press('Enter')
        expect(toggle).to_have_attribute('aria-expanded', 'true')
        expect(body).to_be_visible()
        toggle.press('Space')
        expect(toggle).to_have_attribute('aria-expanded', 'false')
        expect(body).to_be_hidden()
        expect(toggle).to_be_focused()
        case['recipe_disclosure'] = 'Emoji labels restored; missing ingredients stay visible; touch toggles; Enter expands, Space collapses, focus stays on trigger'

        page.locator('.pantry-summary').click()
        page.locator('.pantry-options').scroll_into_view_if_needed()
        for button in page.locator('.pantry-options button').all():
            assert button.bounding_box()['height'] >= 44
        page.locator('.pantry-summary').click()


        # Theme boundaries must also react when the system changes while mounted.
        alternate = 'dark' if theme == 'light' else 'light'
        page.emulate_media(color_scheme=alternate)
        expect(page.locator('.choose-food')).to_have_class(re.compile('ylf-theme-dark') if alternate == 'dark' else re.compile(r'^(?!.*ylf-theme-dark).*$'))
        page.get_by_label('补充食材', exact=True).scroll_into_view_if_needed()
        page.wait_for_timeout(300)
        case['live_theme_text_contrast'] = primary_contrast(page)
        choose_home_view(page, '经典视图')
        assert page.locator('.choose-food:visible').evaluate("e => getComputedStyle(e).getPropertyValue('--ylf-font-body')") == ''
        expect(page.locator('button.vegetable-tag:visible').filter(has_text='番茄')).to_have_attribute('aria-pressed', 'true')
        case['passed'] = True
        report['cases'].append(case)
        print(case, flush=True)
        context.close()
    browser.close()

assert not report['errors'], report['errors']
report['passed'] = True
(OUTPUT / 'validation.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
