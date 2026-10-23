package org.appdevforall.k2go.contentexchange.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pure-JVM unit tests for the grouping / ordering / totalling rule. No Android, no
 * network: the pure static {@code group(...)} is driven directly, and {@code execute()}
 * uses a hand-written fake repository (no mocking framework needed).
 */
public class ListManagedContentUseCaseTest {

    private static ContentItem item(String app, String title, long bytes, String localRef) {
        return new ContentItem(app, "", title, bytes, localRef);
    }

    private static ContentGroup groupOf(ManagedContent mc, String app) {
        for (ContentGroup g : mc.groups()) {
            if (g.app().equals(app)) {
                return g;
            }
        }
        throw new AssertionError("no group for " + app);
    }

    @Test
    public void groupsItemsByAppWithPerGroupAndGrandTotals() {
        List<ContentItem> items = Arrays.asList(
                item("kiwix", "Wikipedia", 100L, "a"),
                item("kiwix", "Wiktionary", 50L, "b"),
                item("calibre", "A Book", 10L, "u1"));
        ManagedContent mc = ListManagedContentUseCase.group(
                Arrays.asList("kiwix", "calibre"), items);

        assertEquals(150L, groupOf(mc, "kiwix").totalBytes());
        assertEquals(2, groupOf(mc, "kiwix").items().size());
        assertEquals(10L, groupOf(mc, "calibre").totalBytes());
        assertEquals(160L, mc.totalBytes());
        assertFalse(mc.isEmpty());
    }

    @Test
    public void installedAppWithNoContentIsAnEmptyGroup() {
        ManagedContent mc = ListManagedContentUseCase.group(
                Arrays.asList("kiwix", "kolibri"),
                Collections.singletonList(item("kiwix", "Wikipedia", 100L, "a")));

        assertTrue(groupOf(mc, "kolibri").isEmpty());
        assertEquals(0L, groupOf(mc, "kolibri").totalBytes());
        assertEquals(100L, mc.totalBytes());
    }

    @Test
    public void itemFromAnUninstalledAppIsStillKept() {
        // /v1/apps lags behind /v1/content: the item must not be silently dropped.
        ManagedContent mc = ListManagedContentUseCase.group(
                Collections.singletonList("kiwix"),
                Collections.singletonList(item("calibre", "A Book", 10L, "u1")));

        assertEquals(10L, groupOf(mc, "calibre").totalBytes());
        assertEquals(10L, mc.totalBytes());
    }

    @Test
    public void preferredAppsOrderFirstThenOthersAlphabetically() {
        List<String> apps = Arrays.asList("zebra", "calibre", "ark", "kiwix", "kolibri");
        ManagedContent mc = ListManagedContentUseCase.group(apps, Collections.emptyList());

        List<String> order = new ArrayList<>();
        for (ContentGroup g : mc.groups()) {
            order.add(g.app());
        }
        assertEquals(Arrays.asList("kiwix", "kolibri", "calibre", "ark", "zebra"), order);
    }

    @Test
    public void itemsWithinAGroupAreOrderedByTitle() {
        List<ContentItem> items = Arrays.asList(
                item("kiwix", "Zebra", 1L, "z"),
                item("kiwix", "apple", 1L, "a"),
                item("kiwix", "Mango", 1L, "m"));
        ManagedContent mc = ListManagedContentUseCase.group(
                Collections.singletonList("kiwix"), items);

        List<String> titles = new ArrayList<>();
        for (ContentItem it : groupOf(mc, "kiwix").items()) {
            titles.add(it.title());
        }
        assertEquals(Arrays.asList("apple", "Mango", "Zebra"), titles);
    }

    @Test
    public void negativeOrZeroBytesDoNotReduceTotals() {
        List<ContentItem> items = Arrays.asList(
                item("kiwix", "Good", 100L, "a"),
                item("kiwix", "Unknown", -1L, "b"),
                item("kiwix", "Zero", 0L, "c"));
        ManagedContent mc = ListManagedContentUseCase.group(
                Collections.singletonList("kiwix"), items);

        assertEquals(100L, groupOf(mc, "kiwix").totalBytes());
        assertEquals(100L, mc.totalBytes());
    }

    @Test
    public void emptyEverythingIsAnEmptyResult() {
        ManagedContent mc = ListManagedContentUseCase.group(
                Collections.emptyList(), Collections.emptyList());
        assertTrue(mc.isEmpty());
        assertEquals(0L, mc.totalBytes());
        assertTrue(mc.groups().isEmpty());
    }

    @Test
    public void executeFetchesAppsAndContentFromTheRepository() throws Exception {
        FakeRepository repo = new FakeRepository();
        repo.apps = Arrays.asList("kiwix");
        repo.content = Collections.singletonList(item("kiwix", "Wikipedia", 100L, "a"));

        ManagedContent mc = new ListManagedContentUseCase(repo).execute();

        assertTrue(repo.fetchAppsCalled);
        assertTrue(repo.fetchContentCalled);
        assertEquals(100L, mc.totalBytes());
    }

    /** Minimal fake of the domain port; storage is not exercised here. */
    private static final class FakeRepository implements ContentRepository {
        List<String> apps = Collections.emptyList();
        List<ContentItem> content = Collections.emptyList();
        boolean fetchAppsCalled = false;
        boolean fetchContentCalled = false;

        @Override
        public List<String> fetchApps() {
            fetchAppsCalled = true;
            return apps;
        }

        @Override
        public List<ContentItem> fetchContent() {
            fetchContentCalled = true;
            return content;
        }

        @Override
        public StorageStatus fetchStorage() {
            return StorageStatus.unavailable();
        }
    }
}
