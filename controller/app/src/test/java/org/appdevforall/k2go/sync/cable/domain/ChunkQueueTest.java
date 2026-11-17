package org.appdevforall.k2go.sync.cable.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class ChunkQueueTest {

    private static TransferChunk.FileEntry f(String p, long s) {
        return new TransferChunk.FileEntry(p, s);
    }

    @Test
    public void bigFilesFirstThenSmallGroups() {
        List<TransferChunk> c = TransferChunk.plan(Arrays.asList(
                f("a", 10), f("big1", 500), f("b", 60), f("big2", 900), f("c", 50), f("d", 5)), 100, 1000);
        assertEquals(4, c.size());
        assertEquals(Arrays.asList("big2"), c.get(0).paths);
        assertEquals(Arrays.asList("big1"), c.get(1).paths);
        assertEquals(Arrays.asList("a", "b", "c"), c.get(2).paths);
        assertEquals(120, c.get(2).bytes);
        assertEquals(Arrays.asList("d"), c.get(3).paths);
    }

    @Test
    public void fileCountCapsAChunk() {
        List<TransferChunk> c = TransferChunk.plan(Arrays.asList(f("a", 1), f("b", 1), f("c", 1)), 100, 2);
        assertEquals(2, c.size());
        assertEquals(2, c.get(0).paths.size());
    }

    @Test
    public void requeuedChunkGoesToTheOtherPath() throws Exception {
        List<TransferChunk> c = TransferChunk.plan(Arrays.asList(f("a", 200), f("b", 200)), 100, 10);
        ChunkQueue q = new ChunkQueue(c);
        TransferChunk cable = q.take();
        TransferChunk wifi = q.take();
        q.requeue(cable);            // cable pulled out
        q.complete(wifi);
        assertSame(cable, q.take()); // Wi-Fi takes it next
        q.complete(cable);
        assertNull(q.take());
        assertTrue(q.isFinished());
        assertEquals(400, q.doneBytes());
        assertEquals(400, q.totalBytes());
    }

    @Test
    public void takeWaitsForARunningChunkThatMayComeBack() throws Exception {
        ChunkQueue q = new ChunkQueue(TransferChunk.plan(Arrays.asList(f("a", 200)), 100, 10));
        TransferChunk only = q.take();
        TransferChunk[] got = new TransferChunk[1];
        Thread other = new Thread(() -> {
            try {
                got[0] = q.take();
            } catch (InterruptedException ignored) {
                // test ends
            }
        });
        other.start();
        Thread.sleep(100);
        q.requeue(only);
        other.join(2000);
        assertSame(only, got[0]);
    }

    @Test
    public void cancelReleasesWaiters() throws Exception {
        ChunkQueue q = new ChunkQueue(TransferChunk.plan(Arrays.asList(f("a", 200)), 100, 10));
        q.take();
        q.cancel();
        assertNull(q.take());
    }

    @Test
    public void dryRunLines() {
        TransferChunk.FileEntry e = DryRunFiles.parse("K2F 1234 library/zims/a b.zim");
        assertEquals("library/zims/a b.zim", e.path);
        assertEquals(1234, e.size);
        assertNull(DryRunFiles.parse("K2F 4096 library/zims/"));
        assertNull(DryRunFiles.parse("sending incremental file list"));
        assertNull(DryRunFiles.parse("K2F x name"));
    }
}
