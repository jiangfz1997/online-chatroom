package com.chatroom.ws;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Wraps a WebSocketSession with a per-client send queue drained by a virtual thread.
 * This mirrors the Go WritePump goroutine + buffered Send channel pattern.
 *
 * Design:
 *  - send(msg) is called from any thread (Hub.broadcast, history fetch, Kafka consumer).
 *  - A single virtual thread drains the queue and writes to the WebSocket session,
 *    avoiding concurrent-write issues on WebSocketSession.
 *  - Call start() after construction to begin draining.
 *  - Call shutdown() on disconnect.
 */
@Slf4j
public class ClientSession {

    // 256-entry buffer matches the Go version's make(chan []byte, 256)
    private static final int QUEUE_CAPACITY = 256;

    @Getter private final WebSocketSession session;
    @Getter private final String username;
    @Getter private final String roomId;

    private final LinkedBlockingQueue<TextMessage> sendQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private volatile boolean running = false;
    private Thread senderThread;

    public ClientSession(WebSocketSession session, String username, String roomId) {
        this.session = session;
        this.username = username;
        this.roomId = roomId;
    }

    public String getSessionId() {
        return session.getId();
    }

    /** Start the virtual thread that drains the send queue. Call once after construction. */
    public void start() {
        running = true;
        senderThread = Thread.ofVirtual()
                .name("ws-sender-" + username)
                .start(this::drainQueue);
    }

    /** Offer a JSON message to the send queue (non-blocking, drops if full). */
    public void send(String json) {
        if (!running || !session.isOpen()) return;
        boolean offered = sendQueue.offer(new TextMessage(json));
        if (!offered) {
            log.warn("Send queue full for user [{}], message dropped", username);
        }
    }

    /** Signal shutdown; the sender thread will exit after draining pending messages. */
    public void shutdown() {
        running = false;
        if (senderThread != null) {
            senderThread.interrupt();
        }
    }

    private void drainQueue() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                TextMessage msg = sendQueue.poll(1, TimeUnit.SECONDS);
                if (msg == null) continue;
                if (session.isOpen()) {
                    session.sendMessage(msg);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                log.warn("WebSocket write failed for user [{}]: {}", username, e.getMessage());
                break;
            }
        }
        log.debug("Sender thread exiting for user [{}]", username);
    }
}
