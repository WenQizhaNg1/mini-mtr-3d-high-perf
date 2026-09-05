import { expect, test } from '@playwright/test';

test('Java live API and app-backed Martin tiles work without Node', async ({ page }) => {
    const apiUrls: string[] = [];
    page.on('request', request => {
        if (new URL(request.url()).pathname.startsWith('/api/')) apiUrls.push(request.url());
    });
    const stream = page.waitForResponse(response => response.url().endsWith('/api/trains/live'));
    await page.goto('/');
    expect((await stream).status()).toBe(200);
    await page.waitForFunction(() => {
        const debug = (window as any).__mtrDebug;
        return debug?.getTrainCount() > 0 && debug.map.getLayer('mtr-stations') && debug.map.isSourceLoaded('mtr')
            && debug.map.queryRenderedFeatures({ layers: ['mtr-stations'] }).length > 0;
    });
    expect(apiUrls.length).toBeGreaterThan(0);
    expect(apiUrls.every(url => new URL(url).port === '3002')).toBeTruthy();
});

for (const theme of ['light', 'dark'] as const) {
    test(`native route, station and train layers at multiple camera angles (${theme})`, async ({ page, request }) => {
        test.setTimeout(60_000);
        await page.emulateMedia({ colorScheme: theme });
        await page.addInitScript(() => localStorage.setItem('mtr-language', 'zh'));
        const response = await request.get('http://127.0.0.1:3002/api/trains?at=2026-09-04T00:00:00Z');
        expect(response.ok()).toBeTruthy();
        const snapshot = await response.json();
        await page.route('**/api/trains/live', route => route.fulfill({
            contentType: 'text/event-stream', body: `data: ${JSON.stringify(snapshot)}\n\n`,
        }));
        const errors: string[] = [];
        page.on('pageerror', error => errors.push(error.message));
        await page.goto('/');
        await page.waitForFunction(() => {
            const debug = (window as any).__mtrDebug;
            return debug?.map.getLayer('mtr-trains') && debug.getTrainCount() > 0;
        });
        for (const camera of [
            { name: 'overview', center: [114.11, 22.36], zoom: 10.5, pitch: 42, bearing: -12 },
            { name: 'corridor', center: [114.165, 22.305], zoom: 13.5, pitch: 42, bearing: -12 },
            { name: 'top', center: [114.165, 22.305], zoom: 14, pitch: 0, bearing: 90 },
            { name: 'tilted', center: [114.165, 22.305], zoom: 14, pitch: 60, bearing: 120 },
        ]) {
            await page.evaluate(camera => (window as any).__mtrDebug.map.jumpTo(camera), camera);
            await page.waitForFunction(() => {
                const map = (window as any).__mtrDebug.map;
                return map.isSourceLoaded('mtr') && map.queryRenderedFeatures({ layers: ['mtr-routes'] }).length > 0;
            });
            await page.waitForTimeout(1000);
            const layers = await page.evaluate(() => {
                const map = (window as any).__mtrDebug.map;
                return {
                    trainType: map.getLayer('mtr-trains').type,
                    font: map.getLayoutProperty('mtr-station-labels', 'text-font'),
                    polygons: map.queryRenderedFeatures({ layers: ['mtr-trains'] }).map((f: any) => f.geometry.type),
                };
            });
            expect(layers.trainType).toBe('fill-extrusion');
            expect(layers.font).toEqual(['Noto Sans CJK SC Bold']);
            expect(layers.polygons.length).toBeGreaterThan(0);
            expect(layers.polygons.every((type: string) => type === 'Polygon' || type === 'MultiPolygon')).toBeTruthy();
            await page.screenshot({ path: `test-results/map-${theme}-${camera.name}.png` });
        }
        await page.setViewportSize({ width: 390, height: 844 });
        await page.screenshot({ path: `test-results/map-${theme}-mobile.png` });
        expect(errors).toEqual([]);
    });
}

// The local Martin/API stack supplies real geometry and replay data.
test('replay, train/station picking, language, current information and live switching', async ({ page, request }) => {
    const errors: string[] = [];
    page.on('pageerror', error => errors.push(error.message));
    const response = await request.get('http://127.0.0.1:3002/api/trains?at=2026-09-04T00:00:00Z');
    expect(response.ok()).toBeTruthy();
    const snapshot = await response.json();
    expect(snapshot.trains.length).toBeGreaterThan(0);
    await page.addInitScript(() => localStorage.setItem('mtr-language', 'en'));
    await page.route('**/api/weather?*', route => route.fulfill({ json: {
        icon: 51, temperatureC: 28, humidityPercent: 80, warnings: ['Weather warning'],
        updatedAt: '2026-09-05T00:00:00Z', cachedAt: '2026-09-05T00:00:00Z', stale: true, error: 'upstream unavailable',
    } }));
    await page.route('**/api/operations', route => route.fulfill({ json: {
        realtime: { enabled: true, healthy: false, lastPollAt: '2026-09-05T00:00:00Z', lastError: 'poll failed', updatedTrains: 0 },
        lines: [{ lineId: 'ISL', incident: { message: 'Service test incident', url: 'javascript:alert(1)', isDelay: true, updatedAt: '2026-09-05T00:00:00Z' } }],
    } }));
    await page.goto('/');
    await expect(page.getByRole('button', { name: 'Pause', exact: true })).toBeEnabled();
    await expect(page.locator('.clock-time')).toBeVisible();
    await page.evaluate(async () => {
        await document.fonts.load('36px "Dotted Songti Square"');
        await document.fonts.load('13px "Chiron Sung HK"');
    });
    expect(await page.evaluate(() => document.fonts.check('36px "Dotted Songti Square"'))).toBeTruthy();
    await page.getByRole('button', { name: 'Pause', exact: true }).click();
    await page.getByLabel('Choose replay date').click();
    await page.locator('input[type=datetime-local]').fill('2026-09-04T08:00');
    await page.locator('input[type=datetime-local]').press('Tab');
    await expect.poll(() => page.evaluate(() => (window as any).__mtrDebug?.getTrainCount())).toBe(snapshot.trains.length);
    await page.getByLabel('Choose replay date').click();
    await page.waitForFunction(() => (window as any).__mtrDebug?.map.isStyleLoaded());
    const train = snapshot.trains[0];
    await page.evaluate(train => {
        const map = (window as any).__mtrDebug.map;
        map.jumpTo({ center: [train.lng, train.lat], zoom: 15 });
    }, train);
    await page.waitForTimeout(1200);
    const point = await page.evaluate(train => {
        const p = (window as any).__mtrDebug.map.project([train.lng, train.lat]);
        return { x: p.x, y: p.y };
    }, train);
    await page.mouse.click(point.x, point.y);
    await expect(page.locator('.selection')).toContainText('Next:');
    await page.screenshot({ path: 'test-results/train-popup.png' });
    await page.getByRole('button', { name: '中文 / English' }).click();
    await expect(page.locator('.selection')).toContainText('下一站');
    await page.getByRole('button', { name: '中文 / English' }).click();
    await page.keyboard.press('Escape');
    await expect(page.locator('.selection')).toHaveCount(0);
    await page.evaluate(() => (window as any).__mtrDebug.map.jumpTo({ center: [114.16, 22.30], zoom: 11 }));
    await page.waitForFunction(() => (window as any).__mtrDebug.map.querySourceFeatures('mtr', { sourceLayer: 'mtr_stations' }).length > 0);
    const station = await page.evaluate(() => {
        const map = (window as any).__mtrDebug.map;
        const feature = map.querySourceFeatures('mtr', { sourceLayer: 'mtr_stations' })[0];
        if (!feature) throw new Error('No station tile loaded');
        map.jumpTo({ center: feature.geometry.coordinates, zoom: 16 });
        return { name: feature.properties.name_en, coordinates: feature.geometry.coordinates };
    });
    await page.waitForTimeout(800);
    const stationPoint = await page.evaluate(station => {
        const p = (window as any).__mtrDebug.map.project(station.coordinates);
        return { x: p.x, y: p.y };
    }, station);
    await page.mouse.click(stationPoint.x, stationPoint.y);
    await expect(page.locator('.selection')).toContainText(station.name);
    await page.screenshot({ path: 'test-results/station-popup.png' });
    await page.keyboard.press('Escape');
    await page.locator('.operations-details > summary').click();
    await expect(page.getByText('Service test incident')).toBeVisible();
    await expect(page.getByText('Weather warning', { exact: true })).toBeVisible();
    await expect(page.getByText('Current information, not historical replay data.')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Official information' })).toHaveCount(0);
    for (let i = 0; i < 4; i++) await page.getByRole('button', { name: 'Replay speed', exact: true }).click();
    await page.getByRole('button', { name: 'Play', exact: true }).click();
    await expect.poll(async () => await page.locator('input[type=datetime-local]').inputValue()).not.toMatch(/^2026-09-04T08:00(:00)?$/);
    await page.getByRole('button', { name: 'Pause', exact: true }).click();
    const paused = await page.locator('input[type=datetime-local]').inputValue();
    await page.waitForTimeout(1300);
    expect(await page.locator('input[type=datetime-local]').inputValue()).toBe(paused);
    await page.screenshot({ path: 'test-results/replay-desktop.png' });
    await page.setViewportSize({ width: 390, height: 844 });
    await expect(page.getByRole('button', { name: 'Go live' })).toBeVisible();
    await page.screenshot({ path: 'test-results/replay-mobile.png' });
    await page.getByRole('button', { name: 'Go live' }).click();
    await expect(page.getByRole('button', { name: 'Replay speed', exact: true })).toBeDisabled();
    expect(errors).toEqual([]);
});

test('original panel metrics, bundled fonts, about modal and compact layout', async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('mtr-language', 'zh'));
    await page.route('**/api/weather?*', route => route.fulfill({ json: {
        icon: 51, temperatureC: 28, humidityPercent: 80, warnings: [],
        updatedAt: '2026-09-05T00:00:00Z', cachedAt: '2026-09-05T00:00:00Z', stale: false, error: null,
    } }));
    await page.route('**/api/operations', route => route.fulfill({ json: {
        realtime: { enabled: true, healthy: true, lastPollAt: '2026-09-05T00:00:00Z', lastError: null, updatedTrains: 100 }, lines: [],
    } }));
    await page.goto('/');
    await expect(page.locator('.api-status')).toContainText('已連接港鐵');
    await page.evaluate(async () => {
        await document.fonts.load('36px "Dotted Songti Square"');
        await document.fonts.load('13px "Chiron Sung HK"');
    });
    await expect(page.locator('.clock-time')).toHaveCSS('font-size', '36px');
    await expect(page.locator('.clock-panel')).toHaveCSS('left', '20px');
    await expect(page.locator('.clock-panel')).toHaveCSS('background-color', 'rgba(15, 23, 42, 0.65)');
    await expect(page.locator('.clock-panel')).toHaveCSS('backdrop-filter', 'blur(12px)');
    await page.waitForFunction(() => (window as any).__mtrDebug?.map.isStyleLoaded());
    await page.screenshot({ path: 'test-results/ui-desktop.png' });
    await page.getByRole('button', { name: '關於', exact: true }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.screenshot({ path: 'test-results/ui-about.png' });
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await page.setViewportSize({ width: 390, height: 844 });
    await expect(page.locator('.timeline-panel')).toHaveCSS('width', '366px');
    await page.screenshot({ path: 'test-results/ui-mobile.png' });
    const controls = await page.locator('.timeline-panel').boundingBox();
    expect(controls!.x).toBeGreaterThanOrEqual(0);
    expect(controls!.x + controls!.width).toBeLessThanOrEqual(390);
});
