package com.example.mafiagame.global.concurrency;

public enum LockType {
    NONE, // 락 없음 (대조군)
    SYNCHRONIZED, // Java synchronized
    PESSIMISTIC, // DB Pessimistic Lock
    OPTIMISTIC, // DB Optimistic Lock (@Version)
    REDISSON_SPIN, // Redis Spin Lock
    REDISSON_PUBSUB // Redis Pub-Sub Lock
}
