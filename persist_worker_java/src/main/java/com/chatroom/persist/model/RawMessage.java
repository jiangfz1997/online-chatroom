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

    // Per-room sequence number (P3 — tmp_doc/05 Track 1). Absent for messages produced by
    // a pre-P3 client/ws-server during a rolling deploy; not indexed in DynamoDB (SK stays
    // {timestamp}#{id}), just carried through for debugging/future backfill.
    private Long seq;
}
