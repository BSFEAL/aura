import './style.css';
import type { Device, Rule, AuraEvent, Status } from './contracts';
import { escapeHtml as esc, eventLabel, ruleText } from './helpers.mjs';

const root = document.querySelector<HTMLDivElement>('#app')!;
let token = ''; // Memory only: refresh deliberately locks the dashboard.
let devices: Device[] = [], rules: Rule[] = [], events: AuraEvent[] = [];
let status: Status | null = null;
let selected = 'Overview';
let message = '';
let busy = false;
async function api<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const response = await fetch(`/api/${path}`, {method, headers: {'Authorization': `Bearer ${token}`, 'Content-Type':'application/json'}, body: body === undefined ? undefined : JSON.stringify(body)});
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(typeof error.detail === 'string' ? error.detail : `Request failed (${response.status}). Check the input and API connection.`);
  }
  return response.status === 204 ? undefined as T : response.json();
}
async function refresh() {
  [devices, rules, events, status] = await Promise.all([api<Device[]>('devices'), api<Rule[]>('rules'), api<AuraEvent[]>('events'), api<Status>('status')]);
}
async function act(operation: () => Promise<unknown>) {
  if (busy) return;
  busy = true;
  try { await operation(); message = ''; await refresh(); }
  catch (error) { message = error instanceof Error ? error.message : 'Connection failed'; }
  finally { busy = false; render(); }
}
function deviceName(id: string) { return devices.find(d => d.id === id)?.name ?? 'Deleted device'; }
function render() {
  if (!token || !status) {
    root.innerHTML = `<main class="login"><div class="orb"></div><p class="eyebrow">KAULA.SYSTEMS / LOCAL FIRST</p><h1>AURA<span>◌</span></h1><h2>Awareness starts here.</h2><p>Connect to your private AURA backend. Monitoring stays under your control.</p><form id="connect"><label>API access token<input name="token" type="password" autocomplete="off" required minlength="24" placeholder="Your local API token"></label><button>Connect dashboard ↗</button></form><p role="alert">${esc(message)}</p><small>Token stays in memory. No microphone access from this dashboard.</small></main>`;
    document.querySelector('#connect')!.addEventListener('submit', event => { event.preventDefault(); token = String(new FormData(event.target as HTMLFormElement).get('token')); void act(async () => { await refresh(); }); });
    return;
  }
  const options = devices.map(d => `<option value="${esc(d.id)}">${esc(d.name)}</option>`).join('');
  root.innerHTML = `<div class="shell"><aside><a class="brand" href="#">AURA<span>◌</span></a><p class="byline">by kaula.systems</p><nav aria-label="Main navigation">${['Overview','Devices','Rules','Events'].map((name, i) => `<button data-page="${name}" class="${selected === name ? 'active' : ''}"><span>${['◈','▣','⌘','≋'][i]}</span>${name}</button>`).join('')}</nav><div class="private"><i></i> LOCAL FIRST<p>Your environment.<br>Your control.</p></div><button id="lock" class="ghost">Lock dashboard</button></aside><main><header><div><p class="eyebrow">YOUR AMBIENT INTELLIGENCE LAYER</p><h1>${selected}</h1></div><button id="refresh" class="ghost">↻ Refresh</button></header>${message ? `<p role="alert" class="error">${esc(message)}</p>` : ''}<section class="hero"><div><p class="eyebrow">AURA / V0.2</p><h2>Stay aware.<br><span>Stay in control.</span></h2><p>Local signals. Meaningful rules. A quieter kind of awareness.</p><div class="badge">◉ &nbsp; Device-controlled monitoring</div></div><div class="radar" aria-hidden="true"><div></div><b>◌</b></div></section><div class="stats"><article><p>Registered devices</p><strong>${status.devices.toString().padStart(2,'0')}</strong><small>Phones & connected sensors</small></article><article><p>Enabled rules</p><strong>${status.enabled_rules.toString().padStart(2,'0')}</strong><small>Amplitude thresholds</small></article><article><p>Recorded events</p><strong>${status.events.toString().padStart(2,'0')}</strong><small>Device reports & simulations</small></article></div>
  <div class="grid">${selected === 'Overview' || selected === 'Events' ? `<section class="panel timeline"><div class="section-title"><h2>Event timeline</h2><span>RECENT 100</span></div>${events.length ? events.map(e => `<article class="event"><div class="event-icon">≋</div><div><h3>Sound-level threshold event</h3><p>${esc(deviceName(e.device_id))} · RMS ${e.rms.toFixed(3)}</p><small>${esc(new Date(e.created_at).toLocaleString())}</small></div><span class="tag ${e.simulated ? 'sim' : ''}">${eventLabel(e)}</span></article>`).join('') : '<div class="empty">◌<h3>Nothing to report yet</h3><p>Start monitoring on your phone, or create a labeled simulation.</p></div>'}</section>` : ''}
  ${selected === 'Overview' || selected === 'Devices' ? `<section class="panel"><div class="section-title"><h2>Your devices</h2><span>${devices.length} REGISTERED</span></div>${devices.map(d => `<article class="device"><div class="device-icon">${d.kind === 'phone' ? '▯' : '◇'}</div><div><h3>${esc(d.name)}</h3><p>${esc(d.kind)} · activation on device</p><small class="mono">${esc(d.id)}</small></div><button class="ghost danger" data-delete="${esc(d.id)}" aria-label="Delete ${esc(d.name)}">×</button></article>`).join('') || '<p class="muted">Register a phone to begin.</p>'}<form id="device-form"><label>Device name<input name="name" required maxlength="80" placeholder="e.g. Living room phone"></label><label>Type<select name="kind"><option value="phone">Android phone</option><option value="sensor">Connected sensor</option></select></label><button>+ Register device</button></form><p class="hint">Registration does not activate a sensor. Connected sensors submit amplitude reports through the authenticated API.</p></section>` : ''}
  ${selected === 'Overview' || selected === 'Rules' ? `<section class="panel"><div class="section-title"><h2>Rule builder</h2><span>LOCAL SIGNALS</span></div><p class="muted">Tell AURA when to notify you.</p><form id="rule-form"><label>Deploy to<select name="device_id" required>${options}</select></label><label>Monitoring rule<textarea name="text" required maxlength="240">${ruleText(0.15)}</textarea></label><button ${devices.length ? '' : 'disabled'}>Create rule ↗</button></form><p class="hint">Supported: “Notify me when sound level is above 0.15”. RMS ranges from 0–1 and is not calibrated decibels. It cannot identify rain, speech, or other sound types. Android downloads rules when you tap Sync.</p>${rules.map(r => `<article class="rule"><div><h3>${esc(r.text)}</h3><p>${esc(deviceName(r.device_id))}</p></div><button class="ghost" data-toggle="${esc(r.id)}">${r.enabled ? 'Disable' : 'Enable'}</button></article>`).join('')}</section>` : ''}
  ${selected === 'Overview' || selected === 'Events' ? `<section class="panel"><div class="section-title"><h2>Test the flow</h2><span>SIMULATION</span></div><p class="muted">Generate a clearly labeled event without using a microphone.</p><form id="simulation"><label>Device<select name="device_id" required>${options}</select></label><label>Normalized RMS<input name="rms" type="number" min="0" max="1" step="0.01" value="0.3" required></label><button class="secondary" ${devices.length ? '' : 'disabled'}>Generate simulated event</button></form><p class="hint">Simulations are manual, never evidence of a real-world sound.</p></section>` : ''}</div><footer>AURA v0.2 · kaula.systems <span>No audio uploaded. No autonomous emergency calls.</span></footer></main></div>`;
  document.querySelectorAll<HTMLButtonElement>('[data-page]').forEach(button => button.onclick = () => { selected = button.dataset.page!; render(); });
  document.querySelector('#lock')!.addEventListener('click', () => { token = ''; status = null; devices = []; rules = []; events = []; render(); });
  document.querySelector('#refresh')!.addEventListener('click', () => void act(async () => {}));
  bindForm('device-form', data => api('devices','POST',{name: data.get('name'), kind: data.get('kind')}));
  bindForm('rule-form', data => api('rules','POST',{device_id:data.get('device_id'), text:data.get('text')}));
  bindForm('simulation', data => api('simulate','POST',{device_id:data.get('device_id'), rms:Number(data.get('rms'))}));
  document.querySelectorAll<HTMLButtonElement>('[data-toggle]').forEach(button => button.onclick = () => void act(() => api(`rules/${button.dataset.toggle}`, 'PATCH', {enabled: !rules.find(r => r.id === button.dataset.toggle)!.enabled})));
  document.querySelectorAll<HTMLButtonElement>('[data-delete]').forEach(button => button.onclick = () => { if (confirm('Delete this device and its API rules and events? Local phone history is retained.')) void act(() => api(`devices/${button.dataset.delete}`, 'DELETE')); });
}
function bindForm(id: string, operation: (data: FormData) => Promise<unknown>) {
  document.getElementById(id)?.addEventListener('submit', event => { event.preventDefault(); const data = new FormData(event.target as HTMLFormElement); void act(() => operation(data)); });
}
render();
