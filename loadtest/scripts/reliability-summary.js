// Prints a loss-rate readout from a reliability.js k6 run's summary.json.
// Invoked by run.sh: node scripts/reliability-summary.js <path-to-summary.json>
const fs = require('fs');

const data = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const m = data.metrics || {};
const count = (name) => {
  const metric = m[name];
  if (!metric) return null;
  const c = metric.values ? metric.values.count : metric.count;
  return typeof c === 'number' ? c : null;
};

const sent = count('chat_messages_sent');
const recvOther = count('chat_messages_received_other');
// Counters with zero observations across the whole run are omitted from k6s
// summary entirely, so a missing gap metric means "zero gaps", not "no data".
const gapOther = count('chat_gap_detected_other') || 0;
const gapOwn = count('chat_gap_detected_own') || 0;
// Only present in P2+ runs (client-side ack/resend) — how many messages the sender
// itself gave up on after exhausting all resends, distinct from what OTHER members
// observed missing (gapOther/gapOwn).
const giveUp = count('chat_resend_giveup');
// Only present in P3+ runs: gap measured against the server's room-wide seq (not the
// per-sender client seq above), and how many times a VU had to reconnect + how many
// sync replies came back truncated (recent-cache retention window too short for the gap).
const roomSeqGap = count('chat_room_seq_gap') || 0;
const reconnectCount = count('chat_reconnect_count') || 0;
const syncTruncated = count('chat_sync_truncated') || 0;

if (sent === null) {
  console.log('(no chat_messages_sent metric found in summary.json -- not a reliability scenario run)');
  process.exit(0);
}

const denom = recvOther + gapOther;
const lossRate = denom > 0 ? gapOther / denom : 0;

console.log(`sent=${sent} recv_other=${recvOther} gap_other=${gapOther} gap_own=${gapOwn}`);
console.log(`cross-instance loss rate ~= ${(lossRate * 100).toFixed(2)}%`);
if (giveUp !== null) {
  console.log(`resend_giveup=${giveUp} (sender gave up after ${giveUp} messages' worth of retries)`);
}
console.log(`room_seq_gap=${roomSeqGap} reconnect_count=${reconnectCount} sync_truncated=${syncTruncated}`);
