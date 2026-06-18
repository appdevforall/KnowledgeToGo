package org.iiab.controller.rootfs.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pure-JVM unit tests for the domain rules. No Android, no network — uses a
 * hand-written fake repository (no mocking framework needed).
 */
public class GetRootfsSizeUseCaseTest {

    private static final long LIVE_OK = 1_428_970_336L;       // ~1.33 GiB, plausible
    private static final long FALLBACK = 1_219_422_532L;      // ~1.14 GiB

    /** Configurable fake of the domain port. */
    private static final class FakeRepository implements RootfsRepository {
        private final long liveBytes;       // <= 0 means "unavailable"
        private final boolean liveFlag;
        boolean fetchLiveCalled = false;
        FakeRepository(long liveBytes, boolean liveFlag) {
            this.liveBytes = liveBytes;
            this.liveFlag = liveFlag;
        }
        @Override
        public Rootfs fetchLive(RootfsTier tier, RootfsAbi abi) {
            fetchLiveCalled = true;
            return new Rootfs(tier, abi, "url", liveBytes, liveFlag);
        }
        @Override
        public Rootfs fallback(RootfsTier tier, RootfsAbi abi) {
            return new Rootfs(tier, abi, "url", FALLBACK, false);
        }
    }

    private Rootfs run(FakeRepository repo) {
        return new GetRootfsSizeUseCase(repo)
                .execute(RootfsTier.STANDARD, RootfsAbi.ARM64_V8A);
    }

    @Test
    public void usesLiveWhenPlausible() {
        Rootfs r = run(new FakeRepository(LIVE_OK, true));
        assertEquals(LIVE_OK, r.sizeBytes());
        assertTrue(r.isLive());
    }

    @Test
    public void skipsLiveWhenOffline() {
        FakeRepository repo = new FakeRepository(LIVE_OK, true); // live would be valid...
        Rootfs r = new GetRootfsSizeUseCase(repo)
                .execute(RootfsTier.STANDARD, RootfsAbi.ARM64_V8A, false); // ...but we're offline
        assertEquals(FALLBACK, r.sizeBytes());
        assertFalse(r.isLive());
        assertFalse("live fetch must be skipped when offline", repo.fetchLiveCalled);
    }

    @Test
    public void fallsBackWhenLiveUnavailable() {
        Rootfs r = run(new FakeRepository(-1, false));
        assertEquals(FALLBACK, r.sizeBytes());
        assertFalse(r.isLive());
    }

    @Test
    public void rejectsZeroAndNegative() {
        assertEquals(FALLBACK, run(new FakeRepository(0, true)).sizeBytes());
        assertEquals(FALLBACK, run(new FakeRepository(-100, true)).sizeBytes());
    }

    @Test
    public void rejectsAbsurdlySmallOrLarge() {
        long tooSmall = 50L * 1024 * 1024;                 // 50 MiB
        long tooLarge = 11L * 1024 * 1024 * 1024;          // 11 GiB
        assertEquals(FALLBACK, run(new FakeRepository(tooSmall, true)).sizeBytes());
        assertEquals(FALLBACK, run(new FakeRepository(tooLarge, true)).sizeBytes());
    }

    @Test
    public void plausibilityBoundsAreInclusive() {
        assertTrue(GetRootfsSizeUseCase.isPlausible(GetRootfsSizeUseCase.MIN_PLAUSIBLE_BYTES));
        assertTrue(GetRootfsSizeUseCase.isPlausible(GetRootfsSizeUseCase.MAX_PLAUSIBLE_BYTES));
        assertFalse(GetRootfsSizeUseCase.isPlausible(GetRootfsSizeUseCase.MIN_PLAUSIBLE_BYTES - 1));
        assertFalse(GetRootfsSizeUseCase.isPlausible(GetRootfsSizeUseCase.MAX_PLAUSIBLE_BYTES + 1));
    }
}
