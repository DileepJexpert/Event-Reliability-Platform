package com.brod.starter;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.Bin;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.ResultCode;
import com.aerospike.client.policy.Policy;
import com.aerospike.client.policy.WritePolicy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the dedup logic against a mocked Aerospike client — no live server needed: a create-only
 * write that succeeds means "first seen"; a {@code KEY_EXISTS_ERROR} means "duplicate"; other errors
 * propagate.
 */
class AerospikeIdempotencyStoreTest {

    @Test
    void firstSeenThenDuplicate() {
        IAerospikeClient client = mock(IAerospikeClient.class);
        when(client.getWritePolicyDefault()).thenReturn(new WritePolicy());
        AerospikeIdempotencyStore store = new AerospikeIdempotencyStore(client, "brod", "idem", 3600);

        // First call: put succeeds (no exception) → newly recorded.
        assertThat(store.markProcessed("txn-1")).isTrue();

        // Second call: Aerospike reports the key already exists → duplicate.
        doThrow(new AerospikeException(ResultCode.KEY_EXISTS_ERROR))
                .when(client).put(any(WritePolicy.class), any(Key.class), any(Bin.class));
        assertThat(store.markProcessed("txn-1")).isFalse();
    }

    @Test
    void otherErrorsPropagate() {
        IAerospikeClient client = mock(IAerospikeClient.class);
        when(client.getWritePolicyDefault()).thenReturn(new WritePolicy());
        AerospikeIdempotencyStore store = new AerospikeIdempotencyStore(client, "brod", "idem", 0);

        doThrow(new AerospikeException(ResultCode.TIMEOUT))
                .when(client).put(any(WritePolicy.class), any(Key.class), any(Bin.class));
        assertThatThrownBy(() -> store.markProcessed("txn-2")).isInstanceOf(AerospikeException.class);
    }

    @Test
    void isProcessedDelegatesToExists() {
        IAerospikeClient client = mock(IAerospikeClient.class);
        when(client.getWritePolicyDefault()).thenReturn(new WritePolicy());
        when(client.getReadPolicyDefault()).thenReturn(new Policy());
        when(client.exists(any(), any(Key.class))).thenReturn(true);
        AerospikeIdempotencyStore store = new AerospikeIdempotencyStore(client, "brod", "idem", 0);

        assertThat(store.isProcessed("txn-9")).isTrue();
    }
}
