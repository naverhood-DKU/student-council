#!/usr/bin/env python3
# naverhood-meetings 버킷에 meetings-cors.json 을 넣는다.
# NCP 콘솔엔 CORS 화면이 없고, 최신 aws cli 는 Content-MD5 를 안 붙여서 NCP 가 거절한다. 그래서 직접 서명한다.
# 사용: NCP_ACCESS_KEY=... NCP_SECRET_KEY=... python3 infra/storage/apply-cors.py
import base64, datetime, hashlib, hmac, json, os, sys, urllib.request
from pathlib import Path

HOST, REGION = 'kr.object.ncloudstorage.com', 'kr-standard'


def cors_xml(rule):
    tags = lambda tag, values: ''.join(f'<{tag}>{v}</{tag}>' for v in values)
    return ('<CORSConfiguration><CORSRule>' + tags('AllowedOrigin', rule['AllowedOrigins'])
            + tags('AllowedMethod', rule['AllowedMethods']) + tags('AllowedHeader', rule['AllowedHeaders'])
            + tags('ExposeHeader', rule['ExposeHeaders'])
            + f"<MaxAgeSeconds>{rule['MaxAgeSeconds']}</MaxAgeSeconds></CORSRule></CORSConfiguration>").encode()


def main(bucket='naverhood-meetings'):
    access, secret = os.environ['NCP_ACCESS_KEY'], os.environ['NCP_SECRET_KEY']
    body = cors_xml(json.loads((Path(__file__).parent / 'meetings-cors.json').read_text())['CORSRules'][0])
    now = datetime.datetime.now(datetime.timezone.utc)
    amz, day = now.strftime('%Y%m%dT%H%M%SZ'), now.strftime('%Y%m%d')
    md5 = base64.b64encode(hashlib.md5(body).digest()).decode()
    payload = hashlib.sha256(body).hexdigest()
    headers = {'content-md5': md5, 'host': HOST, 'x-amz-content-sha256': payload, 'x-amz-date': amz}
    signed = ';'.join(sorted(headers))
    canonical = '\n'.join(['PUT', '/' + bucket, 'cors=', ''.join(f'{k}:{headers[k]}\n' for k in sorted(headers)), signed, payload])
    scope = f'{day}/{REGION}/s3/aws4_request'
    to_sign = '\n'.join(['AWS4-HMAC-SHA256', amz, scope, hashlib.sha256(canonical.encode()).hexdigest()])
    key = ('AWS4' + secret).encode()
    for part in (day, REGION, 's3', 'aws4_request'):
        key = hmac.new(key, part.encode(), hashlib.sha256).digest()
    signature = hmac.new(key, to_sign.encode(), hashlib.sha256).hexdigest()
    request = urllib.request.Request(f'https://{HOST}/{bucket}?cors', data=body, method='PUT', headers={
        'Content-MD5': md5, 'x-amz-content-sha256': payload, 'x-amz-date': amz,
        'Authorization': f'AWS4-HMAC-SHA256 Credential={access}/{scope}, SignedHeaders={signed}, Signature={signature}'})
    print(bucket, urllib.request.urlopen(request, timeout=30).status)


if __name__ == '__main__':
    main(*sys.argv[1:])
