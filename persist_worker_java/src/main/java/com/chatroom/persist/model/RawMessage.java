package com.chatroom.persist.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Matches the JSON envelope written by the ws-server when a message is broadcast. */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RawMessage {
    private String id;
    private String sender;
    private String text;

    @JsonProperty("roomID")
    private String roomId;

    @JsonProperty("sentAt")
    private String timestamp;

    // Per-room sequence number, assigned atomically by the Redis Lua script at consume time
    // (RedisMessageService) — every message reaching this queue has one. This is now the
    // DynamoDB sort key (see MessageRepository): it reflects true Kafka-consumption order,
    // unlike the client-received timestamp, which is stamped per-ws-server-instance before
    // the message even reaches Kafka and can disagree with arrival order across instances.
    private Long seq;
}
