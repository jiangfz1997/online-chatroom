package com.chatroom.repository;

import com.chatroom.model.HistoryMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies getMessagesBefore() queries on seq (the DynamoDB sort key, see MessageRepository's
 * class javadoc for why — seq reflects true Kafka-consumption order, a client-received
 * timestamp doesn't) and that fromMap() reads seq back correctly.
 */
@ExtendWith(MockitoExtension.class)
class MessageRepositoryTest {

    @Mock DynamoDbClient dynamo;

    @Test
    void getMaxSeq_queriesNewestSingleItem() {
        MessageRepository repository = new MessageRepository(dynamo);
        when(dynamo.query(any(QueryRequest.class))).thenReturn(
                QueryResponse.builder().items(List.of(Map.of("seq", AttributeValue.fromN("500")))).build());

        assertThat(repository.getMaxSeq("room-1")).isEqualTo(500L);

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamo).query(captor.capture());
        assertThat(captor.getValue().scanIndexForward()).isFalse();
        assertThat(captor.getValue().limit()).isEqualTo(1);
    }

    @Test
    void getMaxSeq_emptyRoom_returnsZero() {
        MessageRepository repository = new MessageRepository(dynamo);
        when(dynamo.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(List.of()).build());

        assertThat(repository.getMaxSeq("room-1")).isZero();
    }

    @Test
    void getMessagesBefore_withCursor_queriesSeqLessThanBefore() {
        MessageRepository repository = new MessageRepository(dynamo);
        when(dynamo.query(any(QueryRequest.class))).thenReturn(
                QueryResponse.builder()
                        .items(List.of(itemOf("room-1", 7L, "alice", "hi", "2024-01-01T10:00:00Z")))
                        .build());

        List<HistoryMessage> result = repository.getMessagesBefore("room-1", 10L, 20);

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamo).query(captor.capture());
        QueryRequest req = captor.getValue();
        assertThat(req.keyConditionExpression()).isEqualTo("room_id = :rid AND seq < :before");
        assertThat(req.expressionAttributeValues().get(":before")).isEqualTo(AttributeValue.fromN("10"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSeq()).isEqualTo(7L);
        assertThat(result.get(0).getTimestamp()).isEqualTo("2024-01-01T10:00:00Z");
    }

    @Test
    void getMessagesBefore_nullCursor_omitsUpperBound() {
        // No cursor yet (first page): start from the newest message, no seq upper bound.
        MessageRepository repository = new MessageRepository(dynamo);
        when(dynamo.query(any(QueryRequest.class))).thenReturn(
                QueryResponse.builder().items(List.of()).build());

        repository.getMessagesBefore("room-1", null, 20);

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamo).query(captor.capture());
        QueryRequest req = captor.getValue();
        assertThat(req.keyConditionExpression()).isEqualTo("room_id = :rid");
        assertThat(req.expressionAttributeValues()).doesNotContainKey(":before");
    }

    @Test
    void getMessagesBefore_dynamoFailure_returnsEmptyList() {
        MessageRepository repository = new MessageRepository(dynamo);
        when(dynamo.query(any(QueryRequest.class))).thenThrow(new RuntimeException("DynamoDB unavailable"));

        List<HistoryMessage> result = repository.getMessagesBefore("room-1", 10L, 20);

        assertThat(result).isEmpty();
    }

    private Map<String, AttributeValue> itemOf(String roomId, long seq, String sender, String text, String timestamp) {
        return Map.of(
                "room_id",   AttributeValue.fromS(roomId),
                "seq",       AttributeValue.fromN(String.valueOf(seq)),
                "sender",    AttributeValue.fromS(sender),
                "text",      AttributeValue.fromS(text),
                "timestamp", AttributeValue.fromS(timestamp)
        );
    }
}
