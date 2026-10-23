package org.appdevforall.k2go.contentexchange.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

/**
 * Pure-JVM unit tests for the storage use case. The degrade rule ("could not read
 * /v1/storage") must never surface as an error: the use case returns an unavailable
 * status so the manager still lists content.
 */
public class GetStorageStatusUseCaseTest {

    @Test
    public void returnsTheRepositoryStatusWhenAvailable() {
        StorageStatus status = StorageStatus.of("/library", 1000L, 400L, 600L, Collections.emptyList());
        StorageStatus out = new GetStorageStatusUseCase(new FakeRepository(status)).execute();

        assertTrue(out.available());
        assertEquals(400L, out.freeBytes());
        assertEquals(600L, out.usedByContentBytes());
    }

    @Test
    public void passesThroughUnavailableWithoutError() {
        StorageStatus out = new GetStorageStatusUseCase(
                new FakeRepository(StorageStatus.unavailable())).execute();
        assertFalse(out.available());
    }

    @Test
    public void nullFromRepositoryBecomesUnavailable() {
        StorageStatus out = new GetStorageStatusUseCase(new FakeRepository(null)).execute();
        assertFalse(out.available());
    }

    private static final class FakeRepository implements ContentRepository {
        private final StorageStatus storage;

        FakeRepository(StorageStatus storage) {
            this.storage = storage;
        }

        @Override
        public List<String> fetchApps() {
            return Collections.emptyList();
        }

        @Override
        public List<ContentItem> fetchContent() {
            return Collections.emptyList();
        }

        @Override
        public StorageStatus fetchStorage() {
            return storage;
        }
    }
}
