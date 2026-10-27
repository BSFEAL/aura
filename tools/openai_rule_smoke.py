"""Explicit live text-only planner smoke. Never saves/enables a rule or starts monitoring."""
import argparse
import os
from urllib.parse import urlsplit
import httpx


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backend',default='http://127.0.0.1:8000')
    parser.add_argument('--provider',choices=('openai','anthropic'),default='openai')
    parser.add_argument('--allow-send-text',action='store_true',help='Consent to send the built-in non-sensitive example to the selected provider through your backend (may incur API cost)')
    args=parser.parse_args()
    if not args.allow_send_text:
        parser.error('--allow-send-text is required; no network request made')
    uri=urlsplit(args.backend)
    if uri.username or uri.password or uri.query or uri.fragment or not uri.hostname or (uri.scheme!='https' and not (uri.scheme=='http' and uri.hostname in ('127.0.0.1','localhost','::1'))):
        parser.error('Use loopback HTTP or trusted private HTTPS; no credentials in the URL')
    token=os.getenv('AURA_API_TOKEN','')
    if len(token)<24:
        parser.error('Set AURA_API_TOKEN in this terminal; do not pass credentials as arguments')
    try:
        with httpx.Client(timeout=35,follow_redirects=False) as client:
            response=client.post(args.backend.rstrip('/')+'/api/v2/rules/preview',headers={'Authorization':'Bearer '+token},
                json=dict(schema_version=2,device_id='local-smoke-phone',text='Notify me when my dog barks for 2 seconds',provider=args.provider,**{'consent_to_'+args.provider:True}))
        if response.status_code!=200:
            print('Planner smoke failed (HTTP '+str(response.status_code)+'). Check backend configuration; no rule saved.')
            return 1
        value=response.json()
        if value.get('status')!='ready' or value.get('proposal',{}).get('enabled') is not False:
            print('Planner requested clarification or returned no disabled proposal; no rule saved.')
            return 1
        proposal=value['proposal']
        condition=proposal.get('condition',{})
        if (value.get('mode')!=args.provider or condition.get('type')!='audio_event' or condition.get('event_type')!='dog_bark'
                or condition.get('min_duration_ms')!=2000 or value.get('classes')!=[{'class_id':'/m/05tny_','label':'Bark'}]
                or proposal.get('source',{}).get('device_id')!='local-smoke-phone'):
            print('Live planner did not match the expected Bark class and 2-second persistence; no rule saved.')
            return 1
        print('Live planner matched Bark and 2-second persistence. Model: '+str(value.get('planner_model','unknown')))
        print('Review it in the app before saving. No rule was saved by this smoke test.')
        return 0
    except (httpx.HTTPError,ValueError):
        print('Planner smoke failed; no rule saved. Check local backend connectivity.')
        return 1


if __name__=='__main__':
    raise SystemExit(main())
