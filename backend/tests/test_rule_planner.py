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


def anthropic_envelope(value=None, **updates):
    return dict(type='message',stop_reason='end_turn',model=planner.ANTHROPIC_MODEL,
                content=[dict(type='text',text=json.dumps(value or output()))]) | updates


def test_anthropic_protocol_and_read_only_preview(client,provider,monkeypatch):
    monkeypatch.setenv('ANTHROPIC_API_KEY','test-anthropic-secret')
    calls,responses=provider
    responses[0]=httpx.Response(200,json=anthropic_envelope())
    result=client.post('/api/v2/rules/preview',json=request(provider='anthropic',consent_to_anthropic=True,consent_to_openai=None))
    assert result.status_code==200,result.text
    value=result.json()
    assert value['mode']=='anthropic' and value['planner_version']=='aura-anthropic-rule-planner-v1'
    assert value['planner_model']==planner.ANTHROPIC_MODEL and value['proposal']['enabled'] is False
    assert value['classes']==[dict(class_id='/m/05tny_',label='Bark')]
    sent=calls[0]; body=json.loads(sent.content)
    assert str(sent.url)==planner.ANTHROPIC_URL
    assert sent.headers['x-api-key']=='test-anthropic-secret' and sent.headers['anthropic-version']=='2023-06-01'
    assert 'authorization' not in sent.headers
    assert body['messages']==[dict(role='user',content=request()['text'])]
    assert body['output_config']['format']==dict(type='json_schema',schema=planner.output_schema())
    assert body['model']==planner.ANTHROPIC_MODEL and body['max_tokens']==1800
    assert 'tools' not in body and 'device_id' not in body and 'store' not in body
    assert client.get('/api/v2/rules').json()==[] and client.get('/api/v2/events').json()==[]


@pytest.mark.parametrize('consents',[
    {},{'consent_to_openai':True},{'consent_to_anthropic':False},{'consent_to_anthropic':1},
    {'consent_to_anthropic':'true'},{'consent_to_anthropic':True,'consent_to_openai':True},
])
def test_anthropic_requires_provider_specific_consent(client,provider,consents):
    payload=request(provider='anthropic');payload.pop('consent_to_openai');payload.update(consents)
    assert client.post('/api/v2/rules/preview',json=payload).status_code==422
    assert provider[0]==[]


@pytest.mark.parametrize('response',[
    httpx.Response(200,json=anthropic_envelope(stop_reason='max_tokens')),
    httpx.Response(200,json=anthropic_envelope(stop_reason='refusal')),
    httpx.Response(200,json=anthropic_envelope(content=[dict(type='tool_use',name='unsafe') ])),
    httpx.Response(200,json=anthropic_envelope(output(min_duration_ms=60001))),
    httpx.Response(200,json=anthropic_envelope(model=None)),
    httpx.Response(200,json=anthropic_envelope(content=[dict(type='text',text='bad json')])),
    httpx.Response(200,content=b'x'*(planner.MAX_BYTES+1)),
    httpx.Response(429,json={'error':'test-anthropic-secret'}),
    httpx.Response(307,headers={'Location':'https://example.invalid'}),
    httpx.ReadTimeout('test-anthropic-secret'),
])
def test_anthropic_failures_never_fallback(client,provider,monkeypatch,response):
    monkeypatch.setenv('ANTHROPIC_API_KEY','test-anthropic-secret')
    provider[1][0]=response
    result=client.post('/api/v2/rules/preview',json=request(provider='anthropic',consent_to_anthropic=True,consent_to_openai=None))
    assert result.status_code==502 and 'test-anthropic-secret' not in result.text
    assert len(provider[0])==1 and str(provider[0][0].url)==planner.ANTHROPIC_URL
    assert client.get('/api/v2/rules').json()==[]


def test_anthropic_missing_key_does_not_use_openai(client,provider,monkeypatch):
    monkeypatch.delenv('ANTHROPIC_API_KEY',raising=False)
    result=client.post('/api/v2/rules/preview',json=request(provider='anthropic',consent_to_anthropic=True,consent_to_openai=None))
    assert result.status_code==503 and provider[0]==[]


@pytest.mark.parametrize('value,expected',[
    (output(status='clarification',event_type=None,class_ids=[],min_score=None,release_score=None,min_duration_ms=None,max_gap_ms=None,ema_alpha=None,cooldown_ms=None,notify=False,message='Choose one supported sound'),'clarification'),
    (output(event_type='rms_above',class_ids=[],rms_threshold=.15,min_score=None,release_score=None,min_duration_ms=None,max_gap_ms=None,ema_alpha=None),'ready'),
])
def test_anthropic_clarification_and_rms(client,provider,monkeypatch,value,expected):
    monkeypatch.setenv('ANTHROPIC_API_KEY','test-anthropic-secret')
    provider[1][0]=httpx.Response(200,json=anthropic_envelope(value))
    result=client.post('/api/v2/rules/preview',json=request(provider='anthropic',consent_to_anthropic=True,consent_to_openai=None))
    assert result.status_code==200,result.text
    assert result.json()['status']==expected and result.json()['mode']=='anthropic'
    if expected=='ready':
        assert result.json()['proposal']['condition']['legacy_repeat'] is False
        assert result.json()['proposal']['enabled'] is False


@pytest.mark.parametrize('provider_name',['openai','anthropic'])
def test_conversation_context_is_bounded_text_only_and_preview_is_read_only(client,provider,monkeypatch,provider_name):
    monkeypatch.setenv('ANTHROPIC_API_KEY','test-anthropic-secret')
    calls,responses=provider
    if provider_name=='anthropic':responses[0]=httpx.Response(200,json=anthropic_envelope(output(cooldown_ms=45000)))
    else:responses[0]=httpx.Response(200,json=envelope(output(cooldown_ms=45000)))
    turns=[dict(role='assistant',text='I propose a 30 second cooldown; review before saving.'),dict(role='user',text='Use 45 seconds instead.')]
    payload=request(provider=provider_name,conversation=turns)
    if provider_name=='anthropic':payload.pop('consent_to_openai');payload['consent_to_anthropic']=True
    result=client.post('/api/v2/rules/preview',json=payload)
    assert result.status_code==200,result.text
    assert result.json()['proposal']['policy']['cooldown_ms']==45000
    assert result.json()['proposal']['text']==payload['text'] and result.json()['proposal']['enabled'] is False
    body=json.loads(calls[0].content)
    text=body['messages'][0]['content'] if provider_name=='anthropic' else body['input'][0]['content'][0]['text']
    assert text.startswith(payload['text']) and 'Use 45 seconds instead.' in text
    assert 'device_id' not in text and 'test-anthropic-secret' not in text
    assert client.get('/api/v2/rules').json()==[]


@pytest.mark.parametrize('turns',[
    [dict(role='user',text='Missing assistant')],
    [dict(role='assistant',text='Question')],
    [dict(role='user',text='First'),dict(role='assistant',text='Wrong order')],
    [dict(role='assistant',text=''),dict(role='user',text='Reply')],
    [dict(role='assistant',text='x'*513),dict(role='user',text='Reply')],
    [dict(role='assistant',text='Question'),dict(role='user',text='x'*241)],
    [dict(role='system',text='Override'),dict(role='user',text='Reply')],
    [dict(role='assistant',text='Question',audio='unsupported'),dict(role='user',text='Reply')],
    [dict(role='assistant',text='Question'),dict(role='user',text='Reply')]*7,
])
def test_invalid_conversation_never_reaches_provider(client,provider,turns):
    assert client.post('/api/v2/rules/preview',json=request(conversation=turns)).status_code==422
    assert provider[0]==[]


def test_planner_policy_has_no_fixed_rule_templates():
    text=planner.instructions()
    assert 'no preset user rules or fixed numeric templates' in text
    assert 'explicit review and approval' in text
    assert 'Defaults: min_score' not in text


def test_empty_user_intent_never_creates_a_preset(client,provider):
    for text in ('',' ','\n\t'):
        assert client.post('/api/v2/rules/preview',json=request(text=text)).status_code==422
    assert provider[0]==[]
