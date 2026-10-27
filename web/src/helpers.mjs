export function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}
export function eventLabel(event) { return event.simulated || event.provenance === 'simulated' ? 'SIMULATED' : event.provenance === 'model_detected' ? 'MODEL DETECTED · UNVERIFIED' : 'DEVICE REPORT'; }
export function ruleText(threshold) { return `Notify me when sound level is above ${threshold}`; }

export const soundTypes = {dog_bark:'Dog barking',alarm_siren:'Alarm or siren',rain:'Rain',running_water:'Running water / faucet',knocking:'Knocking',doorbell:'Doorbell sound'};
export function eventTitle(event) { return event.event_type && event.event_type !== 'rms_above' ? `Possible ${soundTypes[event.event_type] ?? 'unsupported sound'}` : 'Sound-level threshold event'; }
export function typedRule(deviceId, type, threshold, persistence, gap, cooldown, release = .5, legacyRepeat = false) {
  if (!deviceId || !Number.isFinite(threshold) || threshold <= 0 || threshold > 1) throw new Error('Device and a finite threshold above 0 and at most 1 required');
  if (![persistence,gap,cooldown].every(Number.isInteger) || persistence < 0 || persistence > 60000 || gap < 0 || gap > 10000 || cooldown < 0 || cooldown > 3600000) throw new Error('Invalid duration, gap or cooldown');
  if (type !== 'rms_above' && (!Object.hasOwn(soundTypes,type) || !Number.isFinite(release) || release < 0 || release >= threshold)) throw new Error('Unsupported event or invalid release score');
  return {schema_version:2, enabled:true, source:{device_id:deviceId,modality:'audio'},condition:type==='rms_above'?{type,threshold,legacy_repeat:legacyRepeat}:{type:'audio_event',event_type:type,min_score:threshold,release_score:release,min_duration_ms:persistence,max_gap_ms:gap,ema_alpha:.5,model_version:'yamnet-tflite-1-10c95ea3',mapping_version:'aura-audioset-v1',threshold_version:'provisional-v1'},policy:{cooldown_ms:cooldown,notify:true},text:''};
}
