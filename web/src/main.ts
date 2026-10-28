import './style.css';
import type { Device, Rule, AuraEvent, Status, RulePreview } from './contracts';
import { escapeHtml as esc, eventLabel, ruleText, eventTitle, soundTypes, typedRule, classMap } from './helpers.mjs';

const root = document.querySelector<HTMLDivElement>('#app')!;
let token = ''; // Memory only: refresh deliberately locks the dashboard.
let devices: Device[] = [], rules: Rule[] = [], events: AuraEvent[] = [];
let status: Status | null = null;
let selected = 'Overview';
let message = '';
let busy = false;
let editing: Rule | null = null;
let naturalText = '', naturalInput = '', naturalDevice = '';
let naturalConversation: {role:'user'|'assistant';text:string}[] = [];
let naturalPreview: RulePreview | null = null;
let naturalRevision = 0;
let consentToProvider = false;
let naturalProvider: 'openai'|'anthropic' = 'openai';
const providerName = () => naturalProvider==='anthropic'?'Anthropic':'OpenAI';
async function api<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const response = await fetch(`/api/${path}`, {method, headers: {'Authorization': `Bearer ${token}`, 'Content-Type':'application/json'}, body: body === undefined ? undefined : JSON.stringify(body)});
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(typeof error.detail === 'string' ? error.detail : `Request failed (${response.status}). Check the input and API connection.`);
  }
  return response.status === 204 ? undefined as T : response.json();
}
async function refresh() {
  [devices, rules, events, status] = await Promise.all([api<Device[]>('devices'), api<Rule[]>('v2/rules'), api<AuraEvent[]>('v2/events'), api<Status>('status')]);
}
async function act(operation: () => Promise<unknown>) {
  if (busy) return;
  busy = true;
  const refreshControl=document.querySelector<HTMLButtonElement>('#refresh');
  if(refreshControl) refreshControl.disabled=true;
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
  if (!devices.some(d => d.id === naturalDevice)) { naturalDevice=devices[0]?.id ?? ''; naturalPreview=null; naturalRevision++; }
  const previewMarkup = naturalPreview?.status === 'ready' ? `<div class="rule-preview"><h3>Review proposed rule</h3><p class="hint">${esc(naturalPreview.mode)} model ${esc(naturalPreview.planner_model)} · interpretation unverified</p><p>${esc(naturalPreview.proposal.text)}</p><p>${naturalPreview.classes.map(c => `${esc(c.label)} (${esc(c.class_id)})`).join(' · ') || 'RMS amplitude only; no sound class'}</p><p>${naturalPreview.proposal.condition.type === 'audio_event' ? `Score ${naturalPreview.proposal.condition.min_score} / release ${naturalPreview.proposal.condition.release_score} · persistence ${naturalPreview.proposal.condition.min_duration_ms} ms · gap ${naturalPreview.proposal.condition.max_gap_ms} ms · EMA ${naturalPreview.proposal.condition.ema_alpha}` : `RMS strictly above ${naturalPreview.proposal.condition.threshold} · legacy repeat ${naturalPreview.proposal.condition.legacy_repeat}`}</p><p>Cooldown ${naturalPreview.proposal.policy.cooldown_ms} ms · notify ${naturalPreview.proposal.policy.notify} · saved disabled</p>${naturalPreview.assumptions.map(s => `<p class="hint">${esc(s)}</p>`).join('')}<button id="save-preview">Save disabled rule</button><button id="discard-preview" class="ghost">Discard preview</button></div>` : naturalPreview?.status === 'clarification' ? `<p role="status">Clarification needed: ${esc(naturalPreview.message)}</p>` : '';
  const options = devices.map(d => `<option value="${esc(d.id)}">${esc(d.name)}</option>`).join('');
  root.innerHTML = `<div class="shell"><aside><a class="brand" href="#">AURA<span>◌</span></a><p class="byline">by kaula.systems</p><nav aria-label="Main navigation">${['Overview','Devices','Rules','Events'].map((name, i) => `<button data-page="${name}" class="${selected === name ? 'active' : ''}"><span>${['◈','▣','⌘','≋'][i]}</span>${name}</button>`).join('')}</nav><div class="private"><i></i> LOCAL FIRST<p>Your environment.<br>Your control.</p></div><button id="lock" class="ghost">Lock dashboard</button></aside><main><header><div><p class="eyebrow">YOUR AMBIENT INTELLIGENCE LAYER</p><h1>${selected}</h1></div><button id="refresh" class="ghost" ${busy?'disabled':''}>↻ Refresh</button></header>${message ? `<p role="alert" class="error">${esc(message)}</p>` : ''}<section class="hero"><div><p class="eyebrow">AURA / V0.4</p><h2>Stay aware.<br><span>Stay in control.</span></h2><p>Local signals. Meaningful rules. A quieter kind of awareness.</p><div class="badge">◉ &nbsp; Device-controlled monitoring</div></div><div class="radar" aria-hidden="true"><div></div><b>◌</b></div></section><div class="stats"><article><p>Registered devices</p><strong>${status.devices.toString().padStart(2,'0')}</strong><small>Phones & connected sensors</small></article><article><p>Enabled rules</p><strong>${status.enabled_rules.toString().padStart(2,'0')}</strong><small>Typed audio rules</small></article><article><p>Recorded events</p><strong>${status.events.toString().padStart(2,'0')}</strong><small>Device reports & simulations</small></article></div>
  <div class="grid">${selected === 'Overview' || selected === 'Events' ? `<section class="panel timeline"><div class="section-title"><h2>Event timeline</h2><span>RECENT 100</span></div>${events.length ? events.map(e => `<article class="event"><div class="event-icon">≋</div><div><h3>${esc(eventTitle(e))}</h3><p>${esc(deviceName(e.device_id))} · ${e.event_type === 'rms_above' ? `RMS ${e.rms.toFixed(3)}` : `raw peak ${e.peak_score?.toFixed(3)} · mean ${e.mean_score?.toFixed(3)}`}</p><small>${esc(new Date(e.start_at).toLocaleString())} · ${e.duration_ms} ms</small>${e.event_type !== 'rms_above' ? `<p>${esc(e.class_id)} · ${esc(e.model_version)}<br>Client-reported model evidence · uncalibrated<br>${esc(e.label)} · notification ${esc(e.notification_status)}</p>` : ''}</div><span class="tag ${e.simulated ? 'sim' : ''}">${eventLabel(e)}</span></article>`).join('') : '<div class="empty">◌<h3>Nothing to report yet</h3><p>Start monitoring on your phone, or create a labeled simulation.</p></div>'}</section>` : ''}
  ${selected === 'Overview' || selected === 'Devices' ? `<section class="panel"><div class="section-title"><h2>Your devices</h2><span>${devices.length} REGISTERED</span></div>${devices.map(d => `<article class="device"><div class="device-icon">${d.kind === 'phone' ? '▯' : '◇'}</div><div><h3>${esc(d.name)}</h3><p>${esc(d.kind)} · activation on device</p><small class="mono">${esc(d.id)}</small></div><button class="ghost danger" data-delete="${esc(d.id)}" aria-label="Delete ${esc(d.name)}">×</button></article>`).join('') || '<p class="muted">Register a phone to begin.</p>'}<form id="device-form"><label>Device name<input name="name" required maxlength="80" placeholder="e.g. Living room phone"></label><label>Type<select name="kind"><option value="phone">Android phone</option><option value="sensor">Connected sensor</option></select></label><button>+ Register device</button></form><p class="hint">Registration does not activate a sensor. Connected sensors submit amplitude reports through the authenticated API.</p></section>` : ''}
  ${selected === 'Overview' || selected === 'Rules' ? `<section class="panel"><div class="section-title"><h2>Rule builder</h2><span>LOCAL SIGNALS</span></div><p class="muted">Tell AURA when to notify you.</p><h3>Create a rule through chat</h3><p class="hint">Describe your own event. No event or rule settings are preselected. AURA may propose settings for your approval; chatting never saves or activates a rule.</p><p class="hint">Sending a message shares conversation text and the supported catalog with ${providerName()} through your backend. No microphone audio is sent. The provider key stays on the backend. Provider retention policies apply. Preview never saves or activates a device.</p>${naturalText?`<p><strong>You</strong>: ${esc(naturalText)}</p>`:''}${naturalConversation.map(t=>`<p><strong>${t.role==='user'?'You':'AURA · proposed, unverified'}</strong>: ${esc(t.text)}</p>`).join('')}<form id="nl-preview-form"><label>LLM provider<select name="provider"><option value="openai" ${naturalProvider==='openai'?'selected':''}>OpenAI</option><option value="anthropic" ${naturalProvider==='anthropic'?'selected':''}>Anthropic</option></select></label><label>Preview device<select name="device_id" required><option value="">Select a device</option>${devices.map(d => `<option value="${esc(d.id)}" ${d.id===naturalDevice?'selected':''}>${esc(d.name)}</option>`).join('')}</select></label><label>What should AURA monitor?<textarea name="text" required maxlength="240" placeholder="Describe the event in your own words">${esc(naturalInput)}</textarea></label><label><input type="checkbox" name="consent" required ${consentToProvider?'checked':''}>Allow sharing this conversation with ${providerName()}</label><button ${devices.length?'':'disabled'}>Send to ${providerName()}</button></form><div id="nl-preview" aria-live="polite">${previewMarkup}</div><button id="new-conversation" class="ghost">New conversation</button><p class="hint">Learning from event recordings is a future feature. No recording is captured or uploaded here.</p><hr>${editing?`<h3>Edit existing rule</h3><p class="hint">These are your saved rule settings, not a new rule preset.</p><form id="typed-rule-form">
  <label>Typed rule device<select name="device_id" required>${devices.map(d => `<option value="${esc(d.id)}" ${editing?.source.device_id === d.id ? 'selected' : ''}>${esc(d.name)}</option>`).join('')}</select></label>
  <label>Event type<select name="event_type">${Object.entries({rms_above:'RMS amplitude',...soundTypes}).map(([id,label]) => `<option value="${id}" ${(editing?.condition.type === 'rms_above' ? id === 'rms_above' : id === (editing?.condition.event_type ?? 'dog_bark')) ? 'selected' : ''}>${label}</option>`).join('')}</select></label>
  <label>Class ID filter (blank = max of mapped classes)<input name="class_id" value="${editing?.condition.type === 'audio_event' ? esc(editing.condition.class_id ?? '') : ''}" placeholder="e.g. /m/05tny_"></label>
  <label>Activation threshold<input name="threshold" type="number" min="0.01" max="1" step="0.01" value="${editing.condition.type === 'rms_above' ? editing.condition.threshold : editing.condition.min_score}" required></label>
  <label>Release score<input name="release" type="number" min="0" max="0.99" step="0.01" value="${editing.condition.type === 'audio_event' ? editing.condition.release_score : ''}" ${editing.condition.type==='audio_event'?'required':''}></label>
  <label>Persistence ms<input name="persistence" type="number" min="0" max="60000" value="${editing.condition.type === 'audio_event' ? editing.condition.min_duration_ms : ''}" ${editing.condition.type==='audio_event'?'required':''}></label>
  <label>Max gap ms<input name="gap" type="number" min="0" max="10000" value="${editing.condition.type === 'audio_event' ? editing.condition.max_gap_ms : ''}" ${editing.condition.type==='audio_event'?'required':''}></label>
  <label>Cooldown ms<input name="cooldown" type="number" min="0" max="3600000" value="${editing.policy.cooldown_ms}" required></label>
  <button ${devices.length ? '' : 'disabled'}>Save typed changes</button></form>`:''}
  <p class="hint">Sound scores are uncalibrated and thresholds are provisional. YAMNet runs only after explicit Android activation. Doorbell is a sound class, not a door-opening event. RMS uses only its amplitude threshold; persistence/release controls apply to sound rules.</p><p class="hint">RMS is amplitude, not a sound classifier. Existing RMS rules remain supported. Android downloads rules only when you tap Sync.</p>${rules.map(r => `<article class="rule"><div><h3>${esc(r.text || (r.condition.type === 'rms_above' ? `RMS above ${r.condition.threshold}` : soundTypes[r.condition.event_type]))}</h3><p>${esc(deviceName(r.source.device_id))}</p></div><button class="ghost" data-edit-rule="${esc(r.id)}">Edit</button><button class="ghost danger" data-delete-rule="${esc(r.id)}">Delete</button><button class="ghost" data-toggle="${esc(r.id)}">${r.enabled ? 'Disable' : 'Enable'}</button></article>`).join('')}</section>` : ''}
  ${selected === 'Overview' || selected === 'Events' ? `<section class="panel"><div class="section-title"><h2>Test the flow</h2><span>SIMULATION</span></div><p class="muted">Generate a clearly labeled event without using a microphone.</p><form id="simulation"><label>Device<select name="device_id" required>${options}</select></label><label>Normalized RMS<input name="rms" type="number" min="0" max="1" step="0.01" value="0.3" required></label><button class="secondary" ${devices.length ? '' : 'disabled'}>Generate simulated event</button></form><p class="hint">Simulations are manual, never evidence of a real-world sound.</p></section>` : ''}</div><footer>AURA v0.4 · kaula.systems <span>No audio uploaded. No autonomous emergency calls.</span></footer></main></div>`;
  document.querySelectorAll<HTMLButtonElement>('[data-page]').forEach(button => button.onclick = () => { selected = button.dataset.page!; render(); });
  document.querySelector('#lock')!.addEventListener('click', () => { token = ''; status = null; devices = []; rules = []; events = []; naturalPreview=null;naturalText='';naturalInput='';naturalConversation=[];naturalDevice='';consentToProvider=false;naturalRevision++;render(); });
  document.querySelector('#refresh')!.addEventListener('click', () => void act(async () => {}));
  bindForm('device-form', data => api('devices','POST',{name: data.get('name'), kind: data.get('kind')}));
  const naturalForm = document.querySelector<HTMLFormElement>('#nl-preview-form');
  const invalidatePreview = () => { if (!naturalForm) return; const data=new FormData(naturalForm);const nextProvider=data.get('provider')==='anthropic'?'anthropic':'openai';if(nextProvider!==naturalProvider){naturalProvider=nextProvider;consentToProvider=false;naturalPreview=null;naturalRevision++;render();return;}naturalInput=String(data.get('text'));naturalDevice=String(data.get('device_id'));consentToProvider=data.get('consent')==='on';naturalPreview=null;naturalRevision++;document.querySelector('#nl-preview')?.replaceChildren(); };
  naturalForm?.addEventListener('input',invalidatePreview);
  naturalForm?.addEventListener('change',invalidatePreview);
  bindForm('nl-preview-form', async data => {
    naturalInput=String(data.get('text'));naturalDevice=String(data.get('device_id'));
    if(data.get('consent')!=='on') throw new Error('Explicit consent to selected provider text sharing is required');
    if(!naturalInput.trim() || naturalInput.length>240 || naturalConversation.length>=12) throw new Error('Enter a message up to 240 characters, or start a new conversation');
    const original=naturalText || naturalInput;
    const conversation=naturalText?[...naturalConversation,{role:'user' as const,text:naturalInput}]:[];
    const version=++naturalRevision;naturalPreview=null;
    const value=await api<RulePreview>('v2/rules/preview','POST',{schema_version:2,device_id:naturalDevice,text:original,conversation,provider:naturalProvider,[`consent_to_${naturalProvider}`]:true});
    if(version!==naturalRevision) return;
    if(value.mode!==naturalProvider || value.planner_version!==`aura-${naturalProvider}-rule-planner-v1`) throw new Error('Unexpected provider response');
    const reply=value.status==='clarification'?value.message:JSON.stringify({condition:value.proposal.condition,policy:value.proposal.policy});
    if(reply.length>512) throw new Error('Proposal summary exceeds conversation limit; no rule saved');
    naturalText=original;naturalInput='';naturalPreview=value;
    naturalConversation=[...conversation,{role:'assistant',text:reply}];
  });
  document.querySelector('#new-conversation')?.addEventListener('click',()=>{naturalText='';naturalInput='';naturalConversation=[];naturalPreview=null;consentToProvider=false;naturalRevision++;render();});
  document.querySelector('#discard-preview')?.addEventListener('click',()=>{naturalPreview=null;naturalRevision++;render();});
  document.querySelector('#save-preview')?.addEventListener('click',()=>void act(async()=>{
    if(naturalInput.trim() || naturalPreview?.status!=='ready' || naturalPreview.proposal.text!==naturalText || naturalPreview.proposal.source.device_id!==naturalDevice || naturalPreview.proposal.enabled!==false) throw new Error('Preview this request again before saving');
    await api('v2/rules','POST',naturalPreview.proposal);
    naturalPreview=null;naturalText='';naturalInput='';naturalConversation=[];naturalRevision++;
  }));
  bindForm('typed-rule-form', data => {
    if(!editing) throw new Error('Create new rules through chat');
    const value = typedRule(String(data.get('device_id')),String(data.get('event_type')),Number(data.get('threshold')),Number(data.get('persistence')),Number(data.get('gap')),Number(data.get('cooldown')),Number(data.get('release')),editing?.condition.type === 'rms_above' ? editing.condition.legacy_repeat : false,String(data.get('class_id') ?? '').trim() || null);
    if (editing) { value.text = editing.text; value.enabled = editing.enabled; value.policy.notify = editing.policy.notify; if(value.condition.type === 'audio_event' && editing.condition.type === 'audio_event') value.condition.ema_alpha = editing.condition.ema_alpha; }
    return api(editing ? `v2/rules/${editing.id}` : 'v2/rules',editing ? 'PUT' : 'POST',value).then(() => { editing = null; });
  });
  document.querySelectorAll<HTMLButtonElement>('[data-edit-rule]').forEach(button => button.onclick = () => { editing = rules.find(r => r.id === button.dataset.editRule)!; render(); });
  document.querySelectorAll<HTMLButtonElement>('[data-delete-rule]').forEach(button => button.onclick = () => { if(confirm('Delete this rule? Existing event metadata is retained.')) void act(() => api(`v2/rules/${button.dataset.deleteRule}`, 'DELETE')); });
  bindForm('simulation', data => api('simulate','POST',{device_id:data.get('device_id'), rms:Number(data.get('rms'))}));
  document.querySelectorAll<HTMLButtonElement>('[data-toggle]').forEach(button => button.onclick = () => void act(() => api(`v2/rules/${button.dataset.toggle}`, 'PATCH', {enabled: !rules.find(r => r.id === button.dataset.toggle)!.enabled})));
  document.querySelectorAll<HTMLButtonElement>('[data-delete]').forEach(button => button.onclick = () => { if (confirm('Delete this device and its API rules and events? Local phone history is retained.')) void act(() => api(`devices/${button.dataset.delete}`, 'DELETE')); });
}
function bindForm(id: string, operation: (data: FormData) => Promise<unknown>) {
  document.getElementById(id)?.addEventListener('submit', event => { event.preventDefault(); const data = new FormData(event.target as HTMLFormElement); void act(() => operation(data)); });
}
render();
