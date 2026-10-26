export interface Device { id: string; name: string; kind: 'phone' | 'sensor' }
export interface Rule { id: string; device_id: string; text: string; threshold: number; enabled: boolean }
export interface AuraEvent { id: string; device_id: string; rule_id: string | null; rms: number; simulated: boolean; created_at: string }
export interface Status { version: string; devices: number; enabled_rules: number; events: number; monitoring: string }
