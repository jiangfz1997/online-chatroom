// Reliability / fault-injection scenario (tmp_doc/05 §Track 1, P0-P3): same steady
// per-VU traffic pattern as baseline.js, but each message carries a per-sender
// monotonic `seq` so that message loss caused by a fault injected mid-run (ws2
// restart / redis restart / kafka stop-start — orchestrated by run.sh's FAULT env
// var, NOT by this script) shows up as a detectable hole in the sequence instead of
// just "some messages missing" noise.
//
// Every room member (including the sender) receives every broadcast today, so each
// VU independently tracks, per OTHER sender it has heard from, the last seq seen;
// a jump bigger than +1 means the in-between messages from that sender were lost
// somewhere in the Kafka -> Redis -> Pub/Sub chain (tmp_doc/04's positions B/C/D).
// Self-received messages are tracked separately, in their own counter.
//
// Since P2 (tmp_doc/05), the server no longer broadcasts locally at send time — every
// delivery, including the sender's own copy, goes through the same Kafka round trip,
// and the server sends back a fast 'ack'/'send_error' keyed by the client-generated
// `id`. This scenario mirrors the real frontend's (Chatroom.vue) client-side behavior:
// resend the same id if no ack arrives within 5s, up to 3 times, so it can actually
// measure the "does resending recover the loss" outcome P2 is supposed to deliver —
// not just "does removing local broadcast make a single unretried send more fragile"
// (it does — see the rel-kafka-stop-long-p2-01 finding — the resend logic is what's
// supposed to make up for that).
//
// Since P3 (tmp_doc/05), every message also carries a room-wide (not per-sender) `seq`
// assigned by the server, and this scenario mirrors Chatroom.vue's reconnect: on an
// unexpected ws close, it reconnects with exponential backoff + jitter (1s -> 30s cap)
// and sends {type:'sync', roomID, lastSeq} so the server can replay whatever its
// recent-message cache still has for the gap. `chat_room_seq_gap` is the P3-era
// verification signal: with reconnect+sync working, it should trend to 0 even across
// a ws-instance restart, which is exactly the case P2's real-time resend alone cannot
// cover (a resend only helps if the socket is still open to receive the ack).
//
// This scenario intentionally has no pass/fail thresholds: it's meant to be run
// once per FAULT value and compared, not judged pass/fail on a single run.
//
// Run (from loadtest/), letting run.sh inject the fault mid-run:
//   ./run.sh reliability rel-none-01 -- -e VUS=10 -e CONN_HOLD_MS=60000
//   FAULT=restart-ws2 FAULT_DELAY_S=20 \
//     ./run.sh reliability rel-ws2-restart-01 -- -e VUS=10 -e CONN_HOLD_MS=60000
//   FAULT=restart-redis FAULT_DELAY_S=20 \
//     ./run.sh reliability rel-redis-restart-01 -- -e VUS=10 -e CONN_HOLD_MS=60000
//   FAULT=stop-kafka FAULT_DELAY_S=20 FAULT_DOWNTIME_S=15 \
//     ./run.sh reliability rel-kafka-stop-01 -- -e VUS=10 -e CONN_HOLD_MS=60000

import { Counter, Trend } from 'k6/metrics';
import { WebSocket } from 'k6/experimental/websockets';
import { setInterval, clearInterval, setTimeout, clearTimeout } from 'k6/timers';
import { registerAndLogin, createRoom, joinRoom } from '../lib/auth.js';

const API_URL = __ENV.API_URL || 'http://localhost:8080';
const WS1_URL = __ENV.WS1_URL || 'ws://localhost:8081';
const WS2_URL = __ENV.WS2_URL || 'ws://localhost:8082';
const VUS = parseInt(__ENV.VUS || '10', 10);
const MSG_INTERVAL_MS = parseInt(__ENV.MSG_INTERVAL_MS || '1000', 10);
// Longer default hold than baseline.js: needs to comfortably span run.sh's
// FAULT_DELAY_S + FAULT_DOWNTIME_S window so the fault actually lands mid-traffic.
const CONN_HOLD_MS = parseInt(__ENV.CONN_HOLD_MS || '60000', 10);

const e2eLatency = new Trend('chat_e2e_latency_ms', true);
const messagesSent = new Counter('chat_messages_sent');
const messagesReceivedOwn = new Counter('chat_messages_received_own');
const messagesReceivedOther = new Counter('chat_messages_received_other');
// Count of individual messages inferred lost (sum of gap sizes, not gap events).
const gapDetectedOther = new Counter('chat_gap_detected_other');
const gapDetectedOwn = new Counter('chat_gap_detected_own');
// A message that got zero ack/confirmation after the initial send + all resends —
// the client-side equivalent of "truly gave up", distinct from gapDetected* (which is
// what OTHER room members observed missing).
const resendGiveUp = new Counter('chat_resend_giveup');
// P3: gap measured against the server's room-wide seq (not the per-sender client seq
// above) — the authoritative "did this client ever see every message in the room"
// signal, since it also gets filled in by sync_result replay after a reconnect.
const roomSeqGap = new Counter('chat_room_seq_gap');
// How many reconnect attempts fired (WS closed unexpectedly mid-hold).
const reconnectCount = new Counter('chat_reconnect_count');
// A sync reply arrived flagged truncated=true — the recent-cache's own retention
// window had already evicted part of the gap. Not acted on further in this scenario
// (no fetch_history fallback here); just a diagnostic signal.
const syncTruncated = new Counter('chat_sync_truncated');

const RESEND_TIMEOUT_MS = 5000;
const MAX_RESENDS = 3;
const RECONNECT_BASE_MS = 1000;
const RECONNECT_CAP_MS = 30000;

export const options = {
  scenarios: {
    reliability: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: '5m',
    },
  },
};

export function setup() {
  const roomName = `k6-reliability-${Date.now()}`;
  const users = [];
  let roomId = null;

  for (let i = 0; i < VUS; i++) {
    const username = `k6user_${Date.now()}_${i}`;
    const password = 'k6pass1234';
    const token = registerAndLogin(API_URL, username, password);
    if (i === 0) {
      roomId = createRoom(API_URL, token, roomName, 'k6 reliability scenario');
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

  let seq = 0;
  const lastSeqBySender = {};
  // clientMsgId -> { text, retries, timerId } — mirrors Chatroom.vue's pendingByRoom.
  const pending = {};
  // Room-wide seq (P3) — survives reconnects, drives the sync/gap-pull request.
  let lastRoomSeq = 0;
  let reconnectAttempt = 0;
  let sendIntervalId = null;
  let finalCloseScheduled = false;
  let currentWs = null;
  const endAt = Date.now() + CONN_HOLD_MS;

  function sendEnvelope(id, text, retries) {
    if (currentWs) {
      currentWs.send(JSON.stringify({ type: 'message', id, text }));
    }
    const timerId = setTimeout(() => {
      if (retries >= MAX_RESENDS) {
        delete pending[id];
        resendGiveUp.add(1);
      } else {
        sendEnvelope(id, text, retries + 1);
      }
    }, RESEND_TIMEOUT_MS);
    pending[id] = { text, retries, timerId };
  }

  function clearPending(id) {
    const entry = pending[id];
    if (entry) {
      clearTimeout(entry.timerId);
      delete pending[id];
    }
  }

  function sendNext() {
    seq += 1;
    const id = `${__VU}-${seq}`;
    const inner = JSON.stringify({ vu: __VU, seq, clientSendTs: Date.now() });
    sendEnvelope(id, inner, 0);
    messagesSent.add(1);
  }

  // Shared by both a live 'message' envelope and each item replayed inside a
  // 'sync_result' — same shape either way (server sends the exact cached envelope).
  function applyIncoming(envelope, isReplay) {
    let inner;
    try {
      inner = JSON.parse(envelope.text);
      if (typeof inner.clientSendTs !== 'number' || typeof inner.seq !== 'number') return;
    } catch (e) {
      return;
    }

    if (envelope.id) clearPending(envelope.id);

    if (typeof envelope.seq === 'number') {
      if (envelope.seq > lastRoomSeq + 1) {
        roomSeqGap.add(envelope.seq - lastRoomSeq - 1);
      }
      if (envelope.seq > lastRoomSeq) {
        lastRoomSeq = envelope.seq;
      }
    }

    // A replayed (sync_result) message can be arbitrarily old — recording it against
    // e2e latency would swamp that trend with outage-duration-sized numbers, which
    // measures something different (recovery time) from single-hop delivery latency.
    if (!isReplay) {
      e2eLatency.add(Date.now() - inner.clientSendTs);
    }

    const isOwn = inner.vu === __VU;
    if (isOwn) {
      messagesReceivedOwn.add(1);
    } else {
      messagesReceivedOther.add(1);
    }

    const last = lastSeqBySender[inner.vu];
    if (last !== undefined && inner.seq > last + 1) {
      const gap = inner.seq - last - 1;
      if (isOwn) {
        gapDetectedOwn.add(gap);
      } else {
        gapDetectedOther.add(gap);
      }
    }
    if (last === undefined || inner.seq > last) {
      lastSeqBySender[inner.vu] = inner.seq;
    }
  }

  function scheduleReconnect() {
    const remaining = endAt - Date.now();
    if (remaining <= 0) return;
    reconnectAttempt += 1;
    reconnectCount.add(1);
    const base = Math.min(RECONNECT_BASE_MS * 2 ** (reconnectAttempt - 1), RECONNECT_CAP_MS);
    const jittered = Math.round(base * (0.75 + Math.random() * 0.5));
    const delay = Math.min(remaining, Math.min(RECONNECT_CAP_MS, jittered));
    setTimeout(connect, delay);
  }

  function connect() {
    const ws = new WebSocket(url);
    currentWs = ws;

    ws.addEventListener('open', () => {
      reconnectAttempt = 0;

      // Only a reconnect has a nonzero lastRoomSeq (a fresh first connect relies on
      // the server's normal recent-history push instead) — ask it to replay the gap.
      if (lastRoomSeq > 0) {
        ws.send(JSON.stringify({ type: 'sync', roomID: data.roomId, lastSeq: lastRoomSeq }));
      }

      if (!sendIntervalId) {
        sendIntervalId = setInterval(sendNext, MSG_INTERVAL_MS);
      }
      if (!finalCloseScheduled) {
        finalCloseScheduled = true;
        const remaining = Math.max(0, endAt - Date.now());
        setTimeout(() => {
          clearInterval(sendIntervalId);
          if (currentWs) currentWs.close();
        }, remaining);
      }
    });

    ws.addEventListener('message', (event) => {
      let envelope;
      try {
        envelope = JSON.parse(event.data);
      } catch (e) {
        return; // fetch_history / non-JSON-text payloads — not what this scenario measures
      }

      if (envelope.type === 'ack') {
        // Reached Kafka — stop resending. Still waiting on the full round-trip 'message'
        // below for the actual delivery-confirmed gap-tracking.
        clearPending(envelope.id);
        return;
      }
      if (envelope.type === 'send_error') {
        // Let the resend timer already scheduled by sendEnvelope() handle it — same
        // "wait for the timeout, then resend" behavior as Chatroom.vue's simpler model.
        return;
      }
      if (envelope.type === 'sync_result') {
        if (envelope.truncated) syncTruncated.add(1);
        const messages = Array.isArray(envelope.messages) ? envelope.messages : [];
        for (const raw of messages) {
          applyIncoming(raw, true);
        }
        return;
      }
      if (envelope.type !== 'message') return;

      applyIncoming(envelope, false);
    });

    ws.addEventListener('close', () => {
      scheduleReconnect();
    });

    ws.addEventListener('error', (e) => {
      console.error(`VU ${__VU}: WebSocket error — ${e.error}`);
    });
  }

  connect();
}
