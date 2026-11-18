package com.queue.strategy;

public interface PartitionStrategy {
    int selectPartition(String key, int totalPartitions);
}
