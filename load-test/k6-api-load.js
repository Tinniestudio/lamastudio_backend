// load-test/k6-api-load.js
// Sustained load test for authenticated API endpoints.
// Usage: BASE_URL=http://localhost:8080 TOKEN=<jwt> k6 run load-test/k6-api-load.js
//
// Stages:
//   0 → 1m  ramp up to 20 VUs
//   1m → 4m hold at 20 VUs
//   4m → 5m ramp down to 0

import http from 'k6/http';
import { sleep, check } from 'k6';

export const options = {
  stages: [
    { duration: '1m', target: 20 },
    { duration: '3m', target: 20 },
    { duration: '1m', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<1000', 'p(99)<2000'],
    http_req_failed: ['rate<0.05'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN || '';

const headers = {
  Authorization: `Bearer ${TOKEN}`,
  'Content-Type': 'application/json',
};

export default function () {
  // Public content browse (works with or without a token)
  const contents = http.get(`${BASE_URL}/contents?page=0&size=10`, { headers });
  check(contents, { 'contents 200 or 401': (r) => r.status === 200 || r.status === 401 });

  // Authenticated endpoints — only exercise when a token is provided
  if (TOKEN) {
    const notif = http.get(`${BASE_URL}/notifications/unread-count`, { headers });
    check(notif, { 'unread-count 200': (r) => r.status === 200 });

    const profile = http.get(`${BASE_URL}/users/me`, { headers });
    check(profile, { 'profile 200': (r) => r.status === 200 });
  }

  // Random think time between 1–3 s to simulate realistic user pacing
  sleep(Math.random() * 2 + 1);
}
