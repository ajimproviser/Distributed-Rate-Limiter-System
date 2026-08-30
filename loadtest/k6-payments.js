// Sustained load test for the guarded payment path.
//
//   k6 run loadtest/k6-payments.js
//   k6 run -e RPS=2500 -e BASE_URL=http://localhost:8081 loadtest/k6-payments.js
//
// Uses a constant *arrival rate* rather than a fixed number of virtual users, so
// offered load stays at the target even if latency moves - which is the only way
// to measure a rate limiter honestly. With closed-loop VUs, slow responses would
// quietly reduce the offered rate and the limiter would look better than it is.

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TARGET_RPS = Number(__ENV.RPS || 1700);        // ~102K requests/minute
const DURATION = __ENV.DURATION || '60s';
const MERCHANTS = Number(__ENV.MERCHANTS || 50);

const admitted = new Counter('admitted_requests');
const throttled = new Counter('throttled_requests');
const unexpected = new Rate('unexpected_status');

export const options = {
  scenarios: {
    sustained: {
      executor: 'constant-arrival-rate',
      rate: TARGET_RPS,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.ceil(TARGET_RPS / 10),
      maxVUs: Math.ceil(TARGET_RPS * 2),
    },
  },
  thresholds: {
    // The limiter itself must stay fast even while rejecting traffic; a 429 that
    // takes 200ms to produce is not load shedding.
    'http_req_duration{expected_response:true}': ['p(95)<25', 'p(99)<50'],
    unexpected_status: ['rate<0.001'],
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  // Spread traffic over several merchants so quota is contended, not trivially
  // exhausted by a single subject on the first second.
  const merchant = `merchant-${__VU % MERCHANTS}`;

  const response = http.post(
    `${BASE_URL}/api/v1/payments/authorize`,
    JSON.stringify({ orderId: `ord-${__VU}-${__ITER}`, amount: 149.99, currency: 'INR' }),
    {
      headers: { 'Content-Type': 'application/json', 'X-API-Key': merchant },
      tags: { name: 'authorize' },
    },
  );

  if (response.status === 200) {
    admitted.add(1);
  } else if (response.status === 429) {
    throttled.add(1);
    check(response, {
      'throttled response tells the client when to retry': (r) => !!r.headers['Retry-After'],
    });
  }

  unexpected.add(response.status !== 200 && response.status !== 429);

  check(response, {
    'status is 200 or 429': (r) => r.status === 200 || r.status === 429,
    'quota headers present': (r) => !!r.headers['X-Ratelimit-Limit'],
  });
}

export function handleSummary(data) {
  const allowed = data.metrics.admitted_requests?.values.count ?? 0;
  const rejected = data.metrics.throttled_requests?.values.count ?? 0;
  const total = allowed + rejected;

  const summary = [
    '',
    '=== Rate limiter summary ===============================',
    `  offered load      : ${TARGET_RPS} req/s for ${DURATION}`,
    `  total requests    : ${total}`,
    `  admitted (200)    : ${allowed}`,
    `  throttled (429)   : ${rejected}`,
    `  p95 latency       : ${(data.metrics.http_req_duration?.values['p(95)'] ?? 0).toFixed(2)} ms`,
    `  p99 latency       : ${(data.metrics.http_req_duration?.values['p(99)'] ?? 0).toFixed(2)} ms`,
    '========================================================',
    '',
  ].join('\n');

  return { stdout: summary };
}
