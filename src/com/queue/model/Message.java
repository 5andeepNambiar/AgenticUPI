package com.queue.model;

import java.util.UUID;

/**
 * Represents an immutable message in the queue.
 * All fields are final to guarantee thread-safety across concurrent consumers.
 */
public final class Message {
    private final String messageId;
    private final String key;       // Partition routing key (e.g. "user_123")
    private final String payload;   // The actual business payload (JSON/Text)
    private final long timestamp;

    public Message(String key, String payload) {
        this.messageId = UUID.randomUUID().toString();
        this.key = key;
        this.payload = payload;
        this.timestamp = System.currentTimeMillis();
    }

    public Message(String payload) {
        this(null, payload);
    }

    public String getMessageId() {
        return messageId;
    }

    public String getKey() {
        return key;
    }

    public String getPayload() {
        return payload;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return String.format("Message[id=%s, key=%s, payload='%s', ts=%d]", 
                messageId.substring(0, 8), key, payload, timestamp);
    }
}
