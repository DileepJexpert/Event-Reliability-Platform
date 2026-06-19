package com.brod.starter;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.Bin;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.ResultCode;
import com.aerospike.client.policy.RecordExistsAction;
import com.aerospike.client.policy.WritePolicy;

/**
 * Aerospike-backed {@link IdempotencyStore} — the production dedup store for high-TPS, low-latency
 * deduplication that holds across instances and restarts. {@link #markProcessed} does an atomic
 * create-only write: if the key is new the write succeeds (first time, safe to process); if it already
 * exists Aerospike returns {@code KEY_EXISTS_ERROR} (a duplicate). Keys carry a configurable TTL so the
 * dedup window self-expires without manual cleanup.
 */
public class AerospikeIdempotencyStore implements IdempotencyStore {

    private final IAerospikeClient client;
    private final String namespace;
    private final String set;
    private final WritePolicy createOnly;

    public AerospikeIdempotencyStore(IAerospikeClient client, String namespace, String set, int ttlSeconds) {
        this.client = client;
        this.namespace = namespace;
        this.set = set;
        this.createOnly = new WritePolicy(client.getWritePolicyDefault());
        this.createOnly.recordExistsAction = RecordExistsAction.CREATE_ONLY;
        this.createOnly.expiration = ttlSeconds; // >0 seconds, 0 = namespace default, -1 = never expire
        this.createOnly.sendKey = true;
    }

    @Override
    public boolean markProcessed(String key) {
        try {
            client.put(createOnly, new Key(namespace, set, key), new Bin("ts", System.currentTimeMillis()));
            return true; // created → first time seen
        } catch (AerospikeException ex) {
            if (ex.getResultCode() == ResultCode.KEY_EXISTS_ERROR) {
                return false; // already processed → duplicate
            }
            throw ex;
        }
    }

    @Override
    public boolean isProcessed(String key) {
        return client.exists(client.getReadPolicyDefault(), new Key(namespace, set, key));
    }
}
