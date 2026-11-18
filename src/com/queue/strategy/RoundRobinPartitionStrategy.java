package com.queue.strategy;

import java.util.concurrent.atomic.AtomicInteger;

public class RoundRobinPartitionStrategy implements PartitionStrategy {
    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public int selectPartition(String key, int totalPartitions) {
        if (totalPartitions <= 0) {
            throw new IllegalArgumentException("Total partitions must be positive");
        }
        // Atomic fetch-and-increment with bitwise mask to guarantee non-negative integer
        return (counter.getAndIncrement() & 0x7fffffff) % totalPartitions;
    }
}
