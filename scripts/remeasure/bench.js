// 다시 재기(2026-11) — 경로 하나, 조건 하나를 고정된 동시 사용자 수로 정해진 시간 동안 부른다.
// run.sh가 부른다. 경로 이름과 엔드포인트: docs/remeasure/PATHS.md
//
// 환경 변수
//   MODE      measure(기본) | warmup
//   AXIS      size | concurrency | complexity
//   PATH_NAME http-json | http-bytes | http-proto | grpc-unary   (measure)
//   PARAM     크기(1kb …) 또는 복잡도(simple …)                    (measure)
//   VUS       동시 사용자 수 (기본 10)
//   DURATION  측정 시간 (기본 30s)                                 (measure)
//   TARGETS   "경로|값,경로|값,…" — 워밍업에서 부를 것             (warmup)
//   WARMUP_ITERATIONS  TARGETS 하나마다 부를 횟수 (기본 200)        (warmup)
//   API       apiServer 주소 (기본 http://localhost:8080)

import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const MODE = __ENV.MODE || 'measure';
const AXIS = __ENV.AXIS;
const API = __ENV.API || 'http://localhost:8080';
const VUS = parseInt(__ENV.VUS || '10', 10);

const SIZE_BYTES = {
  '1kb': 1024, '10kb': 10240, '50kb': 51200, '100kb': 102400,
  '200kb': 204800, '500kb': 512000, '1mb': 1048576,
};

const ENDPOINTS = {
  data: {
    'http-json': '/api/data/http/json',
    'http-bytes': '/api/data/http/binary',
    'http-proto': '/api/data/http/proto',
    'grpc-unary': '/api/data/grpc',
  },
  complex: {
    'http-json': '/api/complex/http/json',
    'http-proto': '/api/complex/http/binary',
    'grpc-unary': '/api/complex/grpc',
  },
};

if (!['size', 'concurrency', 'complexity'].includes(AXIS)) {
  throw new Error(`AXIS가 잘못됨: ${AXIS}`);
}
const IS_COMPLEX = AXIS === 'complexity';

function target(pathName, param) {
  const ep = (IS_COMPLEX ? ENDPOINTS.complex : ENDPOINTS.data)[pathName];
  if (!ep) throw new Error(`이 축에 없는 경로: ${AXIS} ${pathName}`);
  if (IS_COMPLEX) {
    return { pathName, param, url: `${API}${ep}?complexity=${param}`, expectBytes: null };
  }
  if (!(param in SIZE_BYTES)) throw new Error(`크기가 잘못됨: ${param}`);
  return { pathName, param, url: `${API}${ep}?size=${param}`, expectBytes: SIZE_BYTES[param] };
}

// apiServer가 응답에 담는 dataServer 호출 구간(서버가 System.nanoTime()으로 잰 값, 응답 필드 latencyMs)
const serverCallMs = new Trend('server_call_ms', true);

let targets;
export let options;
if (MODE === 'warmup') {
  const iterations = parseInt(__ENV.WARMUP_ITERATIONS || '200', 10);
  targets = (__ENV.TARGETS || '').split(',').filter((s) => s).map((s) => {
    const [p, v] = s.split('|');
    return target(p, v);
  });
  if (targets.length === 0) throw new Error('TARGETS가 비어 있음');
  // 반복 번호를 대상 수로 나눈 나머지로 대상을 고르므로 대상마다 정확히 같은 횟수가 된다
  options = {
    scenarios: {
      warmup: { executor: 'shared-iterations', vus: VUS, iterations: iterations * targets.length, maxDuration: '30m' },
    },
  };
} else {
  targets = [target(__ENV.PATH_NAME, __ENV.PARAM)];
  options = {
    scenarios: {
      measure: { executor: 'constant-vus', vus: VUS, duration: __ENV.DURATION || '30s' },
    },
  };
}

export default function () {
  const t = MODE === 'warmup'
    ? targets[exec.scenario.iterationInTest % targets.length]
    : targets[0];
  const res = http.get(t.url);
  let body = null;
  if (res.status === 200) {
    try { body = res.json(); } catch (e) { body = null; }
  }
  if (MODE === 'warmup') return;

  const checks = { 'status 200': () => res.status === 200 };
  if (t.expectBytes !== null) {
    checks['payloadSize = 조건 크기'] = () => body !== null && body.payloadSize === t.expectBytes;
  } else if (t.pathName !== 'grpc-unary') {
    // 복잡도 축 grpc-unary의 serializedSize는 -1(값 없음)이라 보지 않는다
    checks['serializedSize > 0'] = () => body !== null && body.serializedSize > 0;
  }
  check(res, checks);
  if (body !== null && typeof body.latencyMs === 'number') {
    serverCallMs.add(body.latencyMs);
  }
}
