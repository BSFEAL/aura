import { test, expect } from '@playwright/test';

test('private API → device → rule → labeled event, including mobile navigation', async ({ page,request }) => {
  const errors: string[] = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.goto('/');
  await page.getByLabel('API access token').fill('e2e-local-token-at-least-24-characters');
  await page.getByRole('button', { name: 'Connect dashboard' }).click();
  await expect(page.getByRole('heading', { name: 'Overview', exact: true })).toBeVisible();
  await page.getByLabel('Device name').fill('Test phone <safe>');
  await page.getByRole('button', { name: 'Register device' }).click();
  await expect(page.getByRole('heading', { name: 'Test phone <safe>' })).toBeVisible();
  const headers={Authorization:'Bearer e2e-local-token-at-least-24-characters'};
  const devices=await (await request.get('/api/devices',{headers})).json();
  const device=devices.find((d:{name:string})=>d.name==='Test phone <safe>');
  expect((await request.post('/api/rules',{headers,data:{device_id:device.id,text:'Notify me when sound level is above 0.15'}})).status()).toBe(201);
  await page.getByRole('button',{name:'Refresh'}).click();
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
  expect((await request.post('/api/v2/rules',{headers,data:{schema_version:2,source:{device_id:device.id},enabled:true,text:'',condition:{type:'audio_event',event_type:'dog_bark',min_score:.8,release_score:.6,min_duration_ms:2500,max_gap_ms:700,ema_alpha:.4},policy:{cooldown_ms:60000,notify:true}}})).status()).toBe(201);
  await page.getByRole('button',{name:'Refresh'}).click();
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

test('OpenAI protocol preview uses real API validation and explicit disabled save', async ({page,request}) => {
  const headers={Authorization:'Bearer e2e-local-token-at-least-24-characters'};
  const device=await (await request.post('/api/devices',{headers,data:{name:'LLM preview phone'}})).json();
  const before=await (await request.get('/api/v2/rules',{headers})).json();
  const errors:string[]=[];page.on('pageerror',error=>errors.push(error.message));
  await page.goto('/');await page.getByLabel('API access token').fill('e2e-local-token-at-least-24-characters');
  await page.getByRole('button',{name:'Connect dashboard'}).click();
  await page.getByLabel('Preview device').selectOption(device.id);
  await page.getByLabel('What should AURA monitor?').fill('Notify me when my dog barks for 2 seconds');
  await page.getByRole('button',{name:'Send to OpenAI'}).click();
  await expect(page.getByRole('button',{name:'Save disabled rule'})).toHaveCount(0);
  await page.getByLabel('Allow sharing this conversation with OpenAI').check();
  await page.getByRole('button',{name:'Send to OpenAI'}).click();
  await expect(page.getByRole('heading',{name:'Review proposed rule'})).toBeVisible();
  await expect(page.getByText('Bark (/m/05tny_)',{exact:true})).toBeVisible();
  await expect(page.locator('#nl-preview img')).toHaveCount(0);
  expect((await (await request.get('/api/v2/rules',{headers})).json()).length).toBe(before.length);
  await page.getByRole('button',{name:'New conversation',exact:true}).click();
  await page.getByLabel('Allow sharing this conversation with OpenAI').check();
  await page.getByLabel('What should AURA monitor?').fill('Notify me when the door opens');
  await expect(page.getByRole('button',{name:'Save disabled rule'})).toHaveCount(0);
  await page.getByRole('button',{name:'Send to OpenAI'}).click();
  await expect(page.getByRole('status')).toContainText('Clarification needed');
  await expect(page.locator('#nl-preview script')).toHaveCount(0);
  await page.getByRole('button',{name:'New conversation',exact:true}).click();
  await page.getByLabel('Allow sharing this conversation with OpenAI').check();
  await page.getByLabel('What should AURA monitor?').fill('Notify me when my dog barks for 2 seconds');
  await page.getByRole('button',{name:'Send to OpenAI'}).click();
  await page.getByRole('button',{name:'Save disabled rule'}).click();
  await expect(page.getByRole('heading',{name:'Notify me when my dog barks for 2 seconds',exact:true})).toBeVisible();
  const rules=await (await request.get('/api/v2/rules',{headers})).json();
  const created=rules.find((r:{source:{device_id:string}})=>r.source.device_id===device.id);
  expect(created.enabled).toBe(false);expect(created.condition.min_duration_ms).toBe(2000);
  await page.locator('.rule').filter({has:page.getByRole('heading',{name:'Notify me when my dog barks for 2 seconds',exact:true})}).getByRole('button',{name:'Edit',exact:true}).click();
  await page.getByRole('button',{name:'Save typed changes',exact:true}).click();
  const edited=(await (await request.get('/api/v2/rules',{headers})).json()).find((r:{id:string})=>r.id===created.id);
  expect(edited.text).toBe(created.text);expect(edited.enabled).toBe(false);
  await page.setViewportSize({width:390,height:844});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
  expect(errors).toEqual([]);
});


test('Anthropic selection resets consent and preview before explicit disabled save', async ({page,request}) => {
  const headers={Authorization:'Bearer e2e-local-token-at-least-24-characters'};
  const device=await (await request.post('/api/devices',{headers,data:{name:'Anthropic phone'}})).json();
  await page.goto('/');await page.getByLabel('API access token').fill('e2e-local-token-at-least-24-characters');
  await page.getByRole('button',{name:'Connect dashboard'}).click();
  await page.getByLabel('Preview device').selectOption(device.id);
  await page.getByLabel('What should AURA monitor?').fill('Notify me when my dog barks for 2 seconds');
  await page.getByLabel('Allow sharing this conversation with OpenAI').check();
  await page.getByRole('button',{name:'Send to OpenAI'}).click();
  await expect(page.getByRole('button',{name:'Save disabled rule'})).toBeVisible();
  await page.getByLabel('LLM provider').selectOption('anthropic');
  await expect(page.getByRole('button',{name:'Save disabled rule'})).toHaveCount(0);
  await expect(page.getByLabel('Allow sharing this conversation with Anthropic')).not.toBeChecked();
  await page.getByLabel('What should AURA monitor?').fill('Keep that proposal for my review.');
  await page.getByLabel('Allow sharing this conversation with Anthropic').check();
  await page.getByRole('button',{name:'Send to Anthropic'}).click();
  await expect(page.locator('#nl-preview')).toContainText('claude-sonnet-4-6');
  await page.setViewportSize({width:390,height:844});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
  const [saved]=await Promise.all([
    page.waitForResponse(response=>response.url().endsWith('/api/v2/rules') && response.request().method()==='POST'),
    page.getByRole('button',{name:'Save disabled rule'}).click(),
  ]);
  expect(saved.status()).toBe(201);
  const rule=(await (await request.get('/api/v2/rules',{headers})).json()).find((r:{source:{device_id:string}})=>r.source.device_id===device.id);
  expect(rule.enabled).toBe(false);expect(rule.condition.min_duration_ms).toBe(2000);
  await page.setViewportSize({width:390,height:844});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
});


test('blank chat has no preset rule and refinements require explicit approval', async ({page,request}) => {
  const headers={Authorization:'Bearer e2e-local-token-at-least-24-characters'};
  const device=await (await request.post('/api/devices',{headers,data:{name:'User-defined chat phone'}})).json();
  await page.goto('/');await page.getByLabel('API access token').fill('e2e-local-token-at-least-24-characters');
  await page.getByRole('button',{name:'Connect dashboard'}).click();
  await expect(page.getByLabel('What should AURA monitor?')).toHaveValue('');
  await expect(page.getByRole('combobox',{name:'Event type',exact:true})).toHaveCount(0);
  await expect(page.getByLabel('Activation threshold')).toHaveCount(0);
  await expect(page.getByRole('button',{name:'Save disabled rule'})).toHaveCount(0);
  await page.getByLabel('Preview device').selectOption(device.id);
  await page.getByLabel('What should AURA monitor?').fill('Notify me about my pet');
  await page.getByLabel('Allow sharing this conversation with OpenAI').check();
  await page.getByRole('button',{name:'Send to OpenAI'}).click();
  await expect(page.getByRole('status')).toContainText('Which sound');
  await page.getByLabel('What should AURA monitor?').fill('Dog barking for 2 seconds, 45 seconds cooldown');
  await page.getByRole('button',{name:'Send to OpenAI'}).click();
  await expect(page.getByRole('heading',{name:'Review proposed rule'})).toBeVisible();
  await expect(page.locator('#nl-preview')).toContainText('45000 ms');
  expect((await (await request.get('/api/v2/rules',{headers})).json()).filter((r:{source:{device_id:string}})=>r.source.device_id===device.id)).toHaveLength(0);
  const [saved]=await Promise.all([page.waitForResponse(r=>r.url().endsWith('/api/v2/rules')&&r.request().method()==='POST'),page.getByRole('button',{name:'Save disabled rule'}).click()]);
  expect(saved.status()).toBe(201);
  const rule=await saved.json();expect(rule.enabled).toBe(false);expect(rule.policy.cooldown_ms).toBe(45000);
});
