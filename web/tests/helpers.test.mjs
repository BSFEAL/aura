import {test} from 'node:test';
import assert from 'node:assert/strict';
import {escapeHtml, eventLabel, ruleText} from '../src/helpers.mjs';
test('device and rule names cannot inject markup', () => assert.equal(escapeHtml('<img src=x onerror="x">'), '&lt;img src=x onerror=&quot;x&quot;&gt;'));
test('simulation provenance stays visible', () => { assert.equal(eventLabel({simulated:true}), 'SIMULATED'); assert.equal(eventLabel({simulated:false}), 'DEVICE REPORT'); });
test('default rule uses supported grammar', () => assert.equal(ruleText(0.15), 'Notify me when sound level is above 0.15'));
import {typedRule, eventTitle} from '../src/helpers.mjs';
test('typed sound rules pin model and threshold meaning', () => {
  const rule=typedRule('phone','dog_bark',.7,3000,1000,30000);
  assert.equal(rule.schema_version,2);
  assert.equal(rule.condition.model_version,'yamnet-tflite-1-10c95ea3');
  assert.equal(rule.condition.event_type,'dog_bark');
  assert.throws(()=>typedRule('phone','door_open',.7,3000,1000,30000));
  assert.throws(()=>typedRule('phone','dog_bark',.4,3000,1000,30000,.5));
  assert.throws(()=>typedRule('phone','rain',NaN,3000,1000,30000));
});
test('typed RMS preserves explicit legacy semantics', () => assert.equal(typedRule('phone','rms_above',.15,0,0,30000,.5,true).condition.legacy_repeat,true));
test('sound event title and provenance never imply verified certainty', () => {
  assert.equal(eventTitle({event_type:'dog_bark'}),'Possible Dog barking');
  assert.equal(eventLabel({provenance:'model_detected',simulated:false}),'MODEL DETECTED · UNVERIFIED');
  assert.equal(eventLabel({provenance:'simulated'}),'SIMULATED');
  assert.ok(!eventTitle({event_type:'<script>'}).includes('<script>'));
});
