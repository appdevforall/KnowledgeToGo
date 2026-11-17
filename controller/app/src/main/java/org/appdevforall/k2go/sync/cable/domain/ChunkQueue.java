/*
 * ============================================================================
 * Name        : ChunkQueue.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : The shared work queue of a Clone transfer (K2GO-576). Each path
 *               (Wi-Fi, cable) takes the next chunk, so the faster path takes
 *               more. A path that fails puts its chunk back at the front, and
 *               the other path finishes it. Thread-safe. Pure JVM.
 * ============================================================================
 */
package org.appdevforall.k2go.sync.cable.domain;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ChunkQueue {

    private final Deque<TransferChunk> pending = new ArrayDeque<>();
    private final Set<TransferChunk> running = new HashSet<>();
    private final long totalBytes;
    private long doneBytes;
    private boolean cancelled;

    public ChunkQueue(List<TransferChunk> chunks) {
        long total = 0;
        for (TransferChunk c : chunks) {
            pending.add(c);
            total += c.bytes;
        }
        totalBytes = total;
    }

    /**
     * Next chunk to copy. Blocks while the queue is empty but another path still runs a chunk
     * (it can come back). Returns null when every chunk is done or the queue is cancelled.
     */
    public synchronized TransferChunk take() throws InterruptedException {
        while (!cancelled) {
            TransferChunk c = pending.pollFirst();
            if (c != null) {
                running.add(c);
                return c;
            }
            if (running.isEmpty()) return null;
            wait();
        }
        return null;
    }

    public synchronized void complete(TransferChunk c) {
        if (running.remove(c)) doneBytes += c.bytes;
        notifyAll();
    }

    /** The path failed this chunk: another path takes it next. */
    public synchronized void requeue(TransferChunk c) {
        if (running.remove(c)) pending.addFirst(c);
        notifyAll();
    }

    public synchronized void cancel() {
        cancelled = true;
        notifyAll();
    }

    public synchronized boolean isFinished() {
        return pending.isEmpty() && running.isEmpty();
    }

    public long totalBytes() {
        return totalBytes;
    }

    public synchronized long doneBytes() {
        return doneBytes;
    }
}
