// load-test/k6-smoke.js
// Smoke test: verify the API is up and responding correctly under light load.
// Usage:     k6 run load-test/k6-smoke.js
// Custom URL: BASE_URL=https://api.tinniestudio.com k6 run load-test/k6-smoke.js

import http from 'k6/http';
import { sleep, check } from 'k6';

export const options = {
  vus: 5,
  duration: '30s',
  thresholds: {
    http_req_duration: ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
  // Health check (public endpoint — must always be fast)
  const health = http.get(`${BASE_URL}/api/v1/actuator/health`);
  check(health, {
    'health is 200': (r) => r.status === 200,
    'health response time < 200ms': (r) => r.timings.duration < 200,
  });

  // Public content browse
  const contents = http.get(`${BASE_URL}/api/v1/contents?page=0&size=10`);
  check(contents, {
    'contents status 200 or 401': (r) => r.status === 200 || r.status === 401,
  });

  sleep(1);
}
