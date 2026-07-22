import http from 'k6/http';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

/** Registers a new user (ignores "already exists") and logs in, returning the JWT. */
export function registerAndLogin(apiUrl, username, password) {
  http.post(`${apiUrl}/api/register`, JSON.stringify({ username, password }), { headers: JSON_HEADERS });

  const res = http.post(`${apiUrl}/api/login`, JSON.stringify({ username, password }), { headers: JSON_HEADERS });
  if (res.status !== 200) {
    throw new Error(`login failed for ${username}: ${res.status} ${res.body}`);
  }
  return JSON.parse(res.body).token;
}

/** Creates a chatroom as the given (already-authenticated) user and returns its room_id. */
export function createRoom(apiUrl, token, name, description) {
  const res = http.post(
    `${apiUrl}/api/chatrooms`,
    JSON.stringify({ name, isPrivate: false, description }),
    { headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` } }
  );
  if (res.status !== 200) {
    throw new Error(`create room failed: ${res.status} ${res.body}`);
  }
  return JSON.parse(res.body).room_id;
}

/** Joins an existing chatroom as the given (already-authenticated) user. */
export function joinRoom(apiUrl, token, roomId) {
  const res = http.post(
    `${apiUrl}/api/chatrooms/join`,
    JSON.stringify({ chatroom_id: roomId }),
    { headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` } }
  );
  if (res.status !== 200) {
    throw new Error(`join room failed: ${res.status} ${res.body}`);
  }
}
