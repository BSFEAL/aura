import {test} from 'node:test';
import assert from 'node:assert/strict';
import {escapeHtml, eventLabel, ruleText} from '../src/helpers.mjs';
test('device and rule names cannot inject markup', () => assert.equal(escapeHtml('<img src=x onerror="x">'), '&lt;img src=x onerror=&quot;x&quot;&gt;'));
test('simulation provenance stays visible', () => { assert.equal(eventLabel({simulated:true}), 'SIMULATED'); assert.equal(eventLabel({simulated:false}), 'DEVICE REPORT'); });
test('default rule uses supported grammar', () => assert.equal(ruleText(0.15), 'Notify me when sound level is above 0.15'));
