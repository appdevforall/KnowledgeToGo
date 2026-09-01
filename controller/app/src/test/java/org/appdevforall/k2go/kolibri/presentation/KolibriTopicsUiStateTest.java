package org.appdevforall.k2go.kolibri.presentation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.appdevforall.k2go.kolibri.domain.TopicNode;
import org.junit.Test;

import java.util.Collections;

/**
 * Unit tests for {@link KolibriTopicsUiState#isEmpty()} — the call that decides whether a
 * level renders content or the "empty" status. The offline bundle folds a folder's loose
 * leaves into an aggregate, so "no child folders" no longer means "nothing here" (ADFA-5094).
 * Pure JVM, no Android.
 */
public class KolibriTopicsUiStateTest {

    private static final String ROOT = "c150ea1d69495d37b5b0ac6f017e9bfb";
    private static final String CHILD = "23a7dc9c73635cd2abbd3e8aab13c3ca";

    private static KolibriTopicsUiState levelOf(TopicNode node) {
        return KolibriTopicsUiState.level(node, "Folder",
                Collections.<String>emptyList(), Collections.singletonList("Folder"));
    }

    @Test
    public void aLevelWithOnlyLooseResourcesIsNotEmpty() {
        // No sub-folders, but loose resources: must render the aggregate line, not "empty".
        TopicNode loose = TopicNode.fromBundle(ROOT, "Folder", "topic", 100L, 3, 3, 100L, null);
        assertFalse(levelOf(loose).isEmpty());
    }

    @Test
    public void aLevelWithNeitherChildrenNorLooseIsEmpty() {
        TopicNode bare = TopicNode.fromBundle(ROOT, "Folder", "topic", 0L, 0, 0, 0L, null);
        assertTrue(levelOf(bare).isEmpty());
    }

    @Test
    public void aLevelWithChildFoldersIsNotEmpty() {
        TopicNode child = TopicNode.fromBundle(CHILD, "Sub", "topic", 0L, 0, 0, 0L, null);
        TopicNode parent = TopicNode.fromBundle(ROOT, "Folder", "topic", 0L, 1, 0, 0L,
                Collections.singletonList(child));
        assertFalse(levelOf(parent).isEmpty());
    }
}
