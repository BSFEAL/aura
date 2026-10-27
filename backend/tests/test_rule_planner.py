import asyncio
import json
import httpx
import pytest
from fastapi.testclient import TestClient
from app import app
from test_api import client
import rule_planner as planner


def output(**updates):
    value=dict(status='ready',event_type='dog_bark',class_ids=['/m/05tny_'],rms_threshold=None,min_score=.7,release_score=.5,
        min_duration_ms=2000,max_gap_ms=1000,ema_alpha=.5,cooldown_ms=30000,notify=True,message='',assumptions=['Provisional thresholds'])
    return value|updates


def envelope(value=None,**updates):
    return dict(status='completed',model=planner.MODEL,output=[dict(type='message',content=[dict(type='output_text',text=json.dumps(value or output()))])])|updates


@pytest.fixture
def provider(monkeypatch):
    monkeypatch.setenv('AURA_LLM_ENABLED','true');monkeypatch.setenv('OPENAI_API_KEY','test-openai-secret-never-real')
    calls=[]
    response=[httpx.Response(200,json=envelope())]
    def handler(request):
        calls.append(request)
        if isinstance(response[0],Exception): raise response[0]
        return response[0]
    async def dependency():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler),follow_redirects=False) as connection:
            yield connection
    app.dependency_overrides[planner.openai_client]=dependency
    yield calls,response
    app.dependency_overrides.pop(planner.openai_client,None)


def request(**updates):
    return dict(schema_version=2,device_id='phone',text='Notify me when my dog barks for 2 seconds',consent_to_openai=True)|updates


def test_auth_consent_and_missing_config(client,provider,monkeypatch):
    calls,_=provider
    assert TestClient(app).post('/api/v2/rules/preview',json=request()).status_code==401
    for consent in (False,1,'true',None):
        assert client.post('/api/v2/rules/preview',json=request(consent_to_openai=consent)).status_code==422
    assert client.post('/api/v2/rules/preview',json=request()|{'audio':'not accepted'}).status_code==422
    assert client.post('/api/v2/rules/preview',json=request(text='x'*241)).status_code==422
    monkeypatch.delenv('OPENAI_API_KEY')
    assert client.post('/api/v2/rules/preview',json=request()).status_code==503
    monkeypatch.setenv('OPENAI_API_KEY','dummy');monkeypatch.setenv('AURA_LLM_ENABLED','false')
    assert client.post('/api/v2/rules/preview',json=request()).status_code==503
    assert calls==[]


def test_openai_protocol_validation_and_explicit_disabled_save(client,provider):
    calls,_=provider
    result=client.post('/api/v2/rules/preview',json=request())
    assert result.status_code==200,result.text
    value=result.json();proposal=value['proposal']
    assert value['mode']=='openai' and value['planner_model']==planner.MODEL
    assert proposal['enabled'] is False and proposal['source']['device_id']=='phone'
    assert proposal['condition']['min_duration_ms']==2000 and proposal['text']==request()['text']
    assert value['classes']==[dict(class_id='/m/05tny_',label='Bark')]
    assert 'test-openai-secret' not in result.text
    sent=calls[0];body=json.loads(sent.content)
    assert str(sent.url)==planner.URL and sent.headers['Authorization']=='Bearer test-openai-secret-never-real'
    assert body['store'] is False and body['model']==planner.MODEL and body['text']['format']['strict'] is True
    assert body['text']['format']['type']=='json_schema' and body['max_output_tokens']==1800
    assert body['input'][0]['content']==[dict(type='input_text',text=request()['text'])]
    assert 'tools' not in body and 'device_id' not in body and 'source' not in body
    assert client.get('/api/v2/rules').json()==[] and client.get('/api/v2/events').json()==[]
    assert client.post('/api/v2/rules',json=proposal).status_code==404  # preview does not register a device
    device=client.post('/api/devices',json={'name':'Phone'}).json()['id']
    proposal['source']['device_id']=device
    saved=client.post('/api/v2/rules',json=proposal)
    assert saved.status_code==201 and saved.json()['enabled'] is False
    assert client.get('/api/status').json()['enabled_rules']==0


@pytest.mark.parametrize('updates',[
    {'event_type':'door_open'},{'class_ids':['/m/0bt9lr']},{'class_ids':['/m/03kmc9']},{'class_ids':['/m/05tny_','/m/05tny_']},
    {'min_score':float('nan')},{'min_score':.2},{'min_score':1.1},{'release_score':.8},{'min_duration_ms':60001},
    {'min_duration_ms':2.5},{'min_duration_ms':True},{'max_gap_ms':10001},{'cooldown_ms':3600001},{'ema_alpha':0},
    {'cooldown_ms':None},{'rms_threshold':.1},{'notify':'true'},{'activate_microphone':True},{'actions':['call']},
    {'event_type':'rain','class_ids':['/m/06mb1','/m/07r10fb']},
])
def test_invalid_or_unsupported_llm_proposals_are_not_saved(client,provider,updates):
    _,response=provider;response[0]=httpx.Response(200,json=envelope(output(**updates)))
    result=client.post('/api/v2/rules/preview',json=request())
    assert result.status_code==502,result.text
    assert client.get('/api/v2/rules').json()==[] and client.get('/api/v2/events').json()==[]


def test_clarification_refusal_and_rms(client,provider):
    _,response=provider
    clarify=output(status='clarification',event_type=None,class_ids=[],rms_threshold=None,min_score=None,release_score=None,min_duration_ms=None,max_gap_ms=None,ema_alpha=None,cooldown_ms=None,notify=False,message='Do you mean barking?')
    response[0]=httpx.Response(200,json=envelope(clarify))
    result=client.post('/api/v2/rules/preview',json=request(text='Notify me about my dog')).json()
    assert result['status']=='clarification' and 'proposal' not in result
    response[0]=httpx.Response(200,json=envelope(output=[],status='completed')|{'output':[dict(type='message',content=[dict(type='refusal',refusal='declined')])]})
    assert client.post('/api/v2/rules/preview',json=request()).json()['status']=='clarification'
    rms=output(event_type='rms_above',class_ids=[],rms_threshold=.15,min_score=None,release_score=None,min_duration_ms=None,max_gap_ms=None,ema_alpha=None)
    response[0]=httpx.Response(200,json=envelope(rms))
    result=client.post('/api/v2/rules/preview',json=request(text='Notify me above RMS 0.15')).json()
    assert result['proposal']['condition']==dict(type='rms_above',threshold=.15,legacy_repeat=False)


@pytest.mark.parametrize('failure',[
    httpx.Response(401,json={'error':'test-openai-secret-never-real'}),httpx.Response(307,headers={'Location':'https://example.invalid'}),
    httpx.Response(200,content=b'x'*(planner.MAX_BYTES+1)),httpx.Response(200,json=envelope(status='incomplete')),
    httpx.Response(200,json=envelope(model='https://untrusted.invalid')),httpx.Response(200,json=envelope(output=[])),
    httpx.ReadTimeout('test-openai-secret-never-real'),
])
def test_provider_errors_and_bounds_never_fabricate_or_leak(client,provider,failure):
    calls,response=provider;response[0]=failure
    result=client.post('/api/v2/rules/preview',json=request())
    assert result.status_code==502 and 'test-openai-secret-never-real' not in result.text
    assert len(calls)==1 and client.get('/api/v2/rules').json()==[]


def test_busy_planner_is_bounded(client,provider,monkeypatch):
    monkeypatch.setattr(planner,'SLOTS',asyncio.Semaphore(0))
    assert client.post('/api/v2/rules/preview',json=request()).status_code==429
    assert provider[0]==[]


def test_catalog_and_output_schema_are_explicit():
    from pathlib import Path
    manifest=json.loads((Path(__file__).resolve().parents[2]/'models/manifest.json').read_text())
    expected={item['class_id']:item['label'] for items in manifest['events'].values() for item in items}
    assert expected==planner.CLASS_LABELS
    schema=planner.output_schema()
    assert schema['additionalProperties'] is False and set(schema['required'])==set(planner.LlmOutput.model_fields)
    assert 'Bark' in planner.instructions() and 'Doorbell' in planner.instructions()
    fixture=json.loads((Path(__file__).resolve().parents[2]/'contracts/rule_plan_ready.json').read_text())
    assert planner.ReadyPreview.model_validate(fixture).proposal.enabled is False


def test_live_smoke_requires_consent_and_safe_transport(tmp_path):
    import os
    from pathlib import Path
    import subprocess
    import sys
    script=Path(__file__).resolve().parents[2]/'tools/openai_rule_smoke.py'
    env=dict(os.environ);env.pop('AURA_API_TOKEN',None)
    for args in ([],['--allow-send-text','--backend','http://192.0.2.1:8000'],['--allow-send-text']):
        result=subprocess.run([sys.executable,str(script),*args],env=env,capture_output=True,text=True,timeout=5)
        assert result.returncode==2  # Guards return before making a network request.


def test_live_smoke_checks_the_known_prompt_mapping(monkeypatch):
    import importlib.util
    from pathlib import Path
    import sys
    path=Path(__file__).resolve().parents[2]/'tools/openai_rule_smoke.py'
    spec=importlib.util.spec_from_file_location('planner_smoke',path)
    smoke=importlib.util.module_from_spec(spec);spec.loader.exec_module(smoke)
    monkeypatch.setattr(sys,'argv',[str(path),'--allow-send-text'])
    monkeypatch.setenv('AURA_API_TOKEN','test-aura-token-at-least-24')
    value=planner.validated_preview(planner.PreviewInput(**request(device_id='local-smoke-phone')),planner.LlmOutput.model_validate(output()),planner.MODEL).model_dump(mode='json')
    class MockClient:
        def __init__(self,**kwargs): pass
        def __enter__(self): return self
        def __exit__(self,*args): pass
        def post(self,*args,**kwargs): return httpx.Response(200,json=value)
    monkeypatch.setattr(smoke.httpx,'Client',MockClient)
    assert smoke.main()==0
    value['proposal']['condition']['event_type']='rain'
    assert smoke.main()==1
