"""Operate the homepage view picker through its Ionic touch control."""
from playwright.sync_api import expect


def expect_home_view(page, label):
    expect(page.get_by_role('button', name=f'做菜视图, {label}', exact=True)).to_be_visible()


def choose_home_view(page, label):
    # Ionic's internal accessible button is covered by its text; real taps use the host.
    page.locator('ion-select.recipe-view-menu:visible').tap()
    page.locator('ion-popover').get_by_role('radio', name=label, exact=True).tap()
    expect(page.locator('ion-popover')).to_have_count(0)
    expect_home_view(page, label)
