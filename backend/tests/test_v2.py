import json
import sqlite3
from datetime import datetime, timezone, timedelta
import pytest
from fastapi.testclient import TestClient
from app import app
from contracts_v2 import MODEL, MAPPING, THRESHOLDS
from test_api import client


def rule(device, **condition):
    return dict(schema_version=2,source=dict(device_id=device,modality='audio'),condition=dict(type='audio_event',event_type='dog_bark',**condition))


def event(device, **updates):
    value=dict(schema_version=2,device_id=device,client_event_id='episode-id',event_type='dog_bark',class_id='/m/05tny_',label='Possible dog barking',
               peak_score=.8,mean_score=.75,model_version=MODEL,mapping_version=MAPPING,threshold_version=THRESHOLDS,
               provenance='model_detected',start_at='2026-10-26T10:00:00Z',end_at='2026-10-26T10:00:03Z',duration_ms=3000)
    value.update(updates)
    return value


def device(client):
    return client.post('/api/devices',json={'name':'Phone'}).json()['id']


def test_typed_rules_edit_toggle_delete_and_legacy_isolation(client):
    d=device(client)
    response=client.post('/api/v2/rules',json=rule(d))
    assert response.status_code==201,response.text
    result=response.json(); assert result['condition']['model_version']==MODEL
    assert client.get('/api/rules').json()==[]  # v0.2 clients cannot accidentally reinterpret sound rules
    assert client.post('/api/v2/rules/validate',json=rule(d,min_score=.4,release_score=.5)).status_code==422
    assert client.put('/api/v2/rules/'+result['id'],json=rule(d,min_score=.9)).json()['condition']['min_score']==.9
    assert client.patch('/api/v2/rules/'+result['id'],json={'enabled':False}).json()['enabled'] is False
    assert client.delete('/api/v2/rules/'+result['id']).status_code==204
    assert client.get('/api/v2/rules').json()==[]


@pytest.mark.parametrize('condition',[{'event_type':'door_open'},{'min_score':True},{'min_score':1.1},{'min_duration_ms':-1},{'max_gap_ms':10001},{'model_version':'future'},{'ema_alpha':0}])
def test_rule_validation(client,condition):
    value=rule(device(client));value['condition'].update(condition)
    assert client.post('/api/v2/rules',json=value).status_code==422


def test_legacy_rows_are_preserved_by_additive_migration(client, monkeypatch, tmp_path):
    path=tmp_path/'v02.db';c=sqlite3.connect(path)
    c.executescript("""CREATE TABLE devices(id TEXT PRIMARY KEY,name TEXT NOT NULL,kind TEXT NOT NULL);
    CREATE TABLE rules(id TEXT PRIMARY KEY,device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,text TEXT NOT NULL,threshold REAL NOT NULL,enabled INTEGER NOT NULL);
    CREATE TABLE events(id TEXT PRIMARY KEY,device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,rule_id TEXT REFERENCES rules(id) ON DELETE SET NULL,rms REAL NOT NULL,simulated INTEGER NOT NULL,created_at TEXT NOT NULL);
    INSERT INTO devices VALUES ('phone','Old phone','phone');
    INSERT INTO rules VALUES ('legacy-rule','phone','Notify me when RMS above 0.15',0.15,1);
    INSERT INTO events VALUES ('history','phone','legacy-rule',0.2,0,'2026-10-26T00:00:00+00:00');""");c.commit();c.close()
    monkeypatch.setenv('AURA_DB',str(path))
    for _ in range(2):
        result=client.get('/api/v2/rules').json()[0]
        assert result['id']=='legacy-rule' and result['condition']['legacy_repeat'] is True
        assert client.get('/api/v2/events').json()[0]['id']=='history'
        assert client.get('/api/events').json()[0]['rms']==.2
    assert sqlite3.connect(path).execute('PRAGMA user_version').fetchone()[0]==2


def test_typed_event_provenance_idempotency_and_persistence(client):
    d=device(client);r=client.post('/api/v2/rules',json=rule(d)).json()
    value=event(d,rule_id=r['id'])
    response=client.post('/api/v2/events',json=value)
    assert response.status_code==201,response.text
    created=response.json()
    assert created['provenance']=='model_detected' and created['evidence_verified'] is False
    assert client.post('/api/v2/events',json=value).json()['id']==created['id']
    assert client.post('/api/v2/events',json=event(d,rule_id=r['id'],label='changed')).status_code==409
    assert len(client.get('/api/v2/events').json())==1
    assert client.get('/api/events').json()==[]
    simulated=client.post('/api/v2/simulate',json=event(d,client_event_id='simulation')).json()
    assert simulated['simulated'] is True and simulated['provenance']=='simulated'
    client.delete('/api/v2/rules/'+r['id'])
    assert client.get('/api/v2/events').json()[-1]['rule_id'] is None
    client.delete('/api/devices/'+d)
    assert client.get('/api/v2/events').json()==[]


@pytest.mark.parametrize('updates',[{'peak_score':1.1},{'mean_score':.9},{'class_id':'/m/not-real'}, {'event_type':'door_open'},
    {'start_at':'2026-10-26T10:00:00'},{'end_at':'2026-10-26T09:00:00Z'}, {'duration_ms':999}, {'model_version':'unknown'},
    {'provenance':'device_reported'},{'duration_ms':True},{'notification_status':'delivered'}])
def test_event_validation(client,updates):
    assert client.post('/api/v2/events',json=event(device(client),**updates)).status_code==422


def test_ownership_disabled_rules_and_auth(client):
    d=device(client);other=device(client);r=client.post('/api/v2/rules',json=rule(d)).json()
    assert client.post('/api/v2/events',json=event(other,rule_id=r['id'])).status_code==404
    assert client.put('/api/v2/rules/'+r['id'],json=rule(other)).status_code==422
    client.patch('/api/v2/rules/'+r['id'],json={'enabled':False})
    assert client.post('/api/v2/events',json=event(d,rule_id=r['id'])).status_code==422
    assert TestClient(app).get('/api/v2/events').status_code==401
    assert TestClient(app).get('/api/v2/rules').status_code==401


def test_typed_rms_round_trip(client):
    d=device(client)
    value=dict(schema_version=2,device_id=d,event_type='rms_above',rms=.3,provenance='device_reported',start_at='2026-10-26T10:00:00Z',end_at='2026-10-26T10:00:00Z')
    result=client.post('/api/v2/events',json=value)
    assert result.status_code==201,result.text
    assert len(client.get('/api/events').json())==1
