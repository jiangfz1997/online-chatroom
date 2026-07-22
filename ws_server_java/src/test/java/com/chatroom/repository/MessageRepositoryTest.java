package com.chatroom.repository;

import com.chatroom.model.HistoryMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Verifies fromMap() correctly strips the "#{id}" suffix that persist-worker's
 * MessageRepository.save() now appends to the DynamoDB sort key, and passes
 * legacy bare-timestamp rows through unchanged.
 */
@ExtendWith(MockitoExtension.class)
class MessageRepositoryTest {

    @Mock DynamoDbClient dynamo;

    @Test
    void getMessagesBefore_stripsIdSuffixFromCompositeSortKey() {
        MessageRepository repository = new MessageRepository(dynamo);
        when(dynamo.query(any(QueryRequest.class))).thenReturn(
                QueryResponse.builder()
                        .items(List.of(itemOf("room-1", "2024-01-01T10:00:00Z#msg-id-1", "alice", "hi")))
                        .build());

        List<HistoryMessage> result = repository.getMessagesBefore("room-1", "2024-01-01T11:00:00Z", 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTimestamp()).isEqualTo("2024-01-01T10:00:00Z");
    }

    @Test
    void getMessagesBefore_legacyBareTimestamp_passesThroughUnchanged() {
        MessageRepository repository = new MessageRepository(dynamo);
        when(dynamo.query(any(QueryRequest.class))).thenReturn(
                QueryResponse.builder()
                        .items(List.of(itemOf("room-1", "2024-01-01T10:00:00Z", "alice", "hi")))
                        .build());

        List<HistoryMessage> result = repository.getMessagesBefore("room-1", "2024-01-01T11:00:00Z", 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTimestamp()).isEqualTo("2024-01-01T10:00:00Z");
    }

    private Map<String, AttributeValue> itemOf(String roomId, String sortKey, String sender, String text) {
        return Map.of(
                "room_id",   AttributeValue.fromS(roomId),
                "timestamp", AttributeValue.fromS(sortKey),
                "sender",    AttributeValue.fromS(sender),
                "text",      AttributeValue.fromS(text)
        );
    }
}
