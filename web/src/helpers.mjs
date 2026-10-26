export function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}
export function eventLabel(event) { return event.simulated ? 'SIMULATED' : 'DEVICE REPORT'; }
export function ruleText(threshold) { return `Notify me when sound level is above ${threshold}`; }
