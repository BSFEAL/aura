"""Test-only ASGI entry point. Real API/SQLite; OpenAI transport is a fixed fixture, never network."""
import json
import os
from pathlib import Path
import sys
import httpx

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'backend'))
from app import app
import rule_planner as planner

os.environ['AURA_LLM_ENABLED']='true'
os.environ['OPENAI_API_KEY']='e2e-placeholder-never-a-real-key'
os.environ['ANTHROPIC_API_KEY']='e2e-placeholder-never-a-real-key'


def provider(request):
    assert str(request.url) in (planner.URL,planner.ANTHROPIC_URL)
    anthropic=str(request.url)==planner.ANTHROPIC_URL
    body=json.loads(request.content)
    text=body['messages'][0]['content'] if anthropic else body['input'][0]['content'][0]['text']
    if text=='Notify me when my dog barks for 2 seconds':
        value=dict(status='ready',event_type='dog_bark',class_ids=['/m/05tny_'],rms_threshold=None,min_score=.7,release_score=.5,min_duration_ms=2000,max_gap_ms=1000,ema_alpha=.5,cooldown_ms=30000,notify=True,message='',assumptions=['Synthetic browser fixture <img src=x onerror="throw 1">'])
    elif text=='Notify me when the door opens':
        value=dict(status='clarification',event_type=None,class_ids=[],rms_threshold=None,min_score=None,release_score=None,min_duration_ms=None,max_gap_ms=None,ema_alpha=None,cooldown_ms=None,notify=False,message='Door opening is unsupported <script>throw 1</script>',assumptions=[])
    else:
        return httpx.Response(500,json={'error':'Unexpected test prompt'})
    if anthropic:
        return httpx.Response(200,json=dict(type='message',stop_reason='end_turn',model=planner.ANTHROPIC_MODEL,content=[dict(type='text',text=json.dumps(value))]))
    return httpx.Response(200,json={'status':'completed','model':planner.MODEL,'output':[{'type':'message','content':[{'type':'output_text','text':json.dumps(value)}]}]})


async def mock_openai_client():
    async with httpx.AsyncClient(transport=httpx.MockTransport(provider)) as client:
        yield client

app.dependency_overrides[planner.openai_client]=mock_openai_client
