package com.queue.strategy;

public class KeyHashPartitionStrategy implements PartitionStrategy {

    @Override
    public int selectPartition(String key, int totalPartitions) {
        if (totalPartitions <= 0) {
            throw new IllegalArgumentException("Total partitions must be positive");
        }
        if (key == null) {
            return 0;
        }
        // Bitwise AND or Math.abs to protect against negative hashCodes in Java
        return (key.hashCode() & 0x7fffffff) % totalPartitions;
    }
}
