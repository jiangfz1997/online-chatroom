// Baseline load-test scenario (see loadtest plan §3.1): low, steady traffic — the
// reference point every later scenario/tuning pass gets compared against.
//
// Each VU: logs in, connects to ws1 or ws2 (alternating, to force some cross-instance
// traffic even at low volume), then sends one message every MSG_INTERVAL_MS embedding
// its own send timestamp in the message text. On receive, it parses that timestamp back
// out to compute end-to-end delivery latency — this is the same clientSendTs technique
// described in the plan, independent of the server's own sentAt field.
//
// Timers come from the global k6/timers module, not a WebSocket-instance method (this
// k6 version's WebSocket object doesn't have one) — and the VU body registers handlers
// and returns immediately, with NO sleep() call. k6/experimental/websockets keeps the
// iteration alive on its own until ws.close() actually runs; blocking the VU body with
// sleep() fights that model instead of working with it (confirmed against k6's own
// setInterval/ws example in the docs).
//
// Run (from loadtest/) — Docker Desktop doesn't support --network host on
// Windows/Mac, so the app services are reached over host.docker.internal
// (same as Prometheus/Promtail do):
//   docker run --rm -i \
//     -v "$PWD/k6:/scripts" -w /scripts \
//     grafana/k6 run scenarios/baseline.js \
//     -e API_URL=http://host.docker.internal:8080 \
//     -e WS1_URL=ws://host.docker.internal:8081 \
//     -e WS2_URL=ws://host.docker.internal:8082 \
//     -e VUS=20 -e MSG_INTERVAL_MS=1000 -e CONN_HOLD_MS=15000

import { Counter, Trend } from 'k6/metrics';
import { WebSocket } from 'k6/experimental/websockets';
import { setInterval, clearInterval, setTimeout } from 'k6/timers';
import { registerAndLogin, createRoom, joinRoom } from '../lib/auth.js';

const API_URL = __ENV.API_URL || 'http://localhost:8080';
const WS1_URL = __ENV.WS1_URL || 'ws://localhost:8081';
const WS2_URL = __ENV.WS2_URL || 'ws://localhost:8082';
const VUS = parseInt(__ENV.VUS || '20', 10);
const MSG_INTERVAL_MS = parseInt(__ENV.MSG_INTERVAL_MS || '1000', 10);
const CONN_HOLD_MS = parseInt(__ENV.CONN_HOLD_MS || '15000', 10);

const e2eLatency = new Trend('chat_e2e_latency_ms', true);
const messagesSent = new Counter('chat_messages_sent');
const messagesReceived = new Counter('chat_messages_received');
// Rough delivery-confirmation signal: for each VU, how many of ITS OWN sent messages
// came back to it (every room member — including the sender — gets every broadcast).
// Not a full "did every other member see it" check (would need cross-VU coordination
// k6 doesn't give you for free); good enough to catch the Redis-dedup-style bug class.
const selfEchoReceived = new Counter('chat_self_echo_received');

export const options = {
  scenarios: {
    baseline: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: '3m',
    },
  },
  thresholds: {
    chat_e2e_latency_ms: ['p(95)<200'],
    ws_connecting: ['p(95)<1000'],
  },
};

export function setup() {
  const roomName = `k6-baseline-${Date.now()}`;
  const users = [];
  let roomId = null;

  for (let i = 0; i < VUS; i++) {
    const username = `k6user_${Date.now()}_${i}`;
    const password = 'k6pass1234';
    const token = registerAndLogin(API_URL, username, password);
    if (i === 0) {
      roomId = createRoom(API_URL, token, roomName, 'k6 baseline scenario');
    } else {
      joinRoom(API_URL, token, roomId);
    }
    users.push({ username, token });
  }

  return { roomId, users };
}

export default function (data) {
  const user = data.users[__VU - 1];
  const wsBase = __VU % 2 === 0 ? WS2_URL : WS1_URL;
  const url = `${wsBase}/ws/${data.roomId}?token=${user.token}`;

  const ws = new WebSocket(url);
  let seq = 0;

  ws.addEventListener('open', () => {
    const intervalId = setInterval(() => {
      seq += 1;
      const inner = JSON.stringify({ vu: __VU, seq, clientSendTs: Date.now() });
      ws.send(JSON.stringify({ type: 'message', text: inner }));
      messagesSent.add(1);
    }, MSG_INTERVAL_MS);

    setTimeout(() => {
      clearInterval(intervalId);
      ws.close();
    }, CONN_HOLD_MS);
  });

  ws.addEventListener('message', (event) => {
    messagesReceived.add(1);
    try {
      const envelope = JSON.parse(event.data);
      const inner = JSON.parse(envelope.text);
      if (typeof inner.clientSendTs !== 'number') return;

      e2eLatency.add(Date.now() - inner.clientSendTs);
      if (inner.vu === __VU) {
        selfEchoReceived.add(1);
      }
    } catch (e) {
      // fetch_history / non-JSON-text payloads — not what this scenario measures
    }
  });

  ws.addEventListener('error', (e) => {
    console.error(`VU ${__VU}: WebSocket error — ${e.error}`);
  });
}
