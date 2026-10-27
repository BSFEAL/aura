import { test, expect } from '@playwright/test';

test('private API → device → rule → labeled event, including mobile navigation', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.goto('/');
  await page.getByLabel('API access token').fill('e2e-local-token-at-least-24-characters');
  await page.getByRole('button', { name: 'Connect dashboard' }).click();
  await expect(page.getByRole('heading', { name: 'Overview', exact: true })).toBeVisible();
  await page.getByLabel('Device name').fill('Test phone <safe>');
  await page.getByRole('button', { name: 'Register device' }).click();
  await expect(page.getByRole('heading', { name: 'Test phone <safe>' })).toBeVisible();
  await page.getByRole('button', { name: 'Create rule' }).click();
  await expect(page.getByRole('button', { name: 'Disable', exact: true })).toHaveCount(1);
  await page.getByRole('button', { name: 'Generate simulated event' }).click();
  await expect(page.getByText('SIMULATED', { exact: true })).toHaveCount(1);
  await page.getByRole('button', { name: 'Disable', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Enable', exact: true })).toHaveCount(1);
  await page.screenshot({ path: '/tmp/aura-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('button', { name: '≋ Events', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Events', exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: '/tmp/aura-mobile.png', fullPage: true });
  await page.getByRole('button', { name: 'Lock dashboard' }).click();
  await expect(page.getByLabel('API access token')).toBeVisible();
  expect(errors).toEqual([]);
});

test('typed sound rule and unverified model event remain distinct from simulations', async ({page,request}) => {
  const token='e2e-local-token-at-least-24-characters';
  const headers={Authorization:`Bearer ${token}`};
  const device=await (await request.post('/api/devices',{headers,data:{name:'Sound test phone'}})).json();
  await page.goto('/');
  await page.getByLabel('API access token').fill(token);
  await page.getByRole('button',{name:'Connect dashboard'}).click();
  await page.getByLabel('Typed rule device').selectOption(device.id);
  await page.getByRole('combobox',{name:'Event type',exact:true}).selectOption('dog_bark');
  await page.getByRole('button',{name:'Create typed rule',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Dog barking',exact:true})).toBeVisible();
  const rules=await (await request.get('/api/v2/rules',{headers})).json();
  const rule=rules.find((r:{source:{device_id:string}})=>r.source.device_id===device.id);
  expect(rule.condition.type).toBe('audio_event');
  const data={schema_version:2,device_id:device.id,event_type:'dog_bark',class_id:'/m/05tny_',label:'<img src=x onerror="throw 1">',peak_score:.8,mean_score:.75,
    model_version:'yamnet-tflite-1-10c95ea3',mapping_version:'aura-audioset-v1',threshold_version:'provisional-v1',provenance:'model_detected',
    start_at:'2026-10-26T10:00:00Z',end_at:'2026-10-26T10:00:03Z',duration_ms:3000};
  expect((await request.post('/api/v2/events',{headers,data})).ok()).toBe(true);
  expect((await request.post('/api/v2/simulate',{headers,data})).ok()).toBe(true);
  await page.getByRole('button',{name:'Refresh'}).click();
  await expect(page.getByText('MODEL DETECTED · UNVERIFIED',{exact:true})).toBeVisible();
  await expect(page.getByRole('heading',{name:'Possible Dog barking',exact:true})).toHaveCount(2);
  await expect(page.locator('.timeline img')).toHaveCount(0);
  await page.setViewportSize({width:390,height:844});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
});
