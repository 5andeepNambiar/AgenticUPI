package com.queue.core;

import com.queue.model.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * An ordered, append-only commit log for messages.
 * Uses ReentrantReadWriteLock to allow multiple parallel readers while
 * ensuring mutual exclusion during append writes.
 */
public class Partition {
    private final int partitionId;
    private final List<Message> messages;
    private final ReentrantReadWriteLock lock;

    public Partition(int partitionId) {
        this.partitionId = partitionId;
        this.messages = new ArrayList<>();
        this.lock = new ReentrantReadWriteLock();
    }

    public int getPartitionId() {
        return partitionId;
    }

    /**
     * Appends a message to the end of this partition log.
     * Returns the offset (index) assigned to this message.
     */
    public int append(Message message) {
        lock.writeLock().lock();
        try {
            int offset = messages.size();
            messages.add(message);
            return offset;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Reads a message at a specific offset.
     * Returns null if the offset is beyond the current log end.
     */
    public Message read(int offset) {
        lock.readLock().lock();
        try {
            if (offset < 0 || offset >= messages.size()) {
                return null;
            }
            return messages.get(offset);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the current total count of messages in this partition.
     */
    public int size() {
        lock.readLock().lock();
        try {
            return messages.size();
        } finally {
            lock.readLock().unlock();
        }
    }
}
