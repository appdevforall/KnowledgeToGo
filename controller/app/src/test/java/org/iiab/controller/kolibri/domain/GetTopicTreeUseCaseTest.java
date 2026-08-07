package org.iiab.controller.kolibri.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Unit tests for {@link GetTopicTreeUseCase} — chiefly that a level which failed
 * to arrive is never mistaken for a level that is genuinely empty. Pure JVM.
 */
public class GetTopicTreeUseCaseTest {

    private static final String ROOT = "c150ea1d69495d37b5b0ac6f017e9bfb";
    private static final String MATH = "6277aa0c44235435acdc8a9ed98f466b";
    private static final String VIDEO = "f9d3e0e46ea25789bbed672ff6a399ed";

    private static final long MB = 1024L * 1024L;

    /** Answers with whatever it was handed; records what was asked for. */
    private static final class FakeRepository implements CatalogRepository {
        final TopicNode answer;
        String asked;
        int calls;

        FakeRepository(TopicNode answer) {
            this.answer = answer;
        }

        @Override
        public List<Channel> channels() {
            return Collections.emptyList();
        }

        @Override
        public String catalogGeneratedOn() {
            return "";
        }

        @Override
        public TopicNode fetchTree(String nodeId) {
            calls++;
            asked = nodeId;
            return answer;
        }
    }

    private static TopicNode leaf(String id, String title, long bytes) {
        return TopicNode.of(id, title, "video", true, bytes, 0, null);
    }

    @Test
    public void aLevelThatArrivesCarriesItsChildrenInSourceOrder() {
        TopicNode a = leaf(MATH, "Unit 1", 3 * MB);
        TopicNode b = leaf(VIDEO, "Unit 2", 4 * MB);
        TopicNode root = TopicNode.of(ROOT, "Channel", TopicNode.KIND_TOPIC, false,
                0L, 2, Arrays.asList(a, b));

        GetTopicTreeUseCase.Result r =
                new GetTopicTreeUseCase(new FakeRepository(root)).execute(ROOT);

        assertFalse(r.isUnavailable());
        assertFalse(r.isEmpty());
        assertEquals("Channel", r.title());
        assertEquals(2, r.children().size());
        // The author's sequence, not size order: unit 1 before unit 2.
        assertEquals("Unit 1", r.children().get(0).title());
        assertEquals("Unit 2", r.children().get(1).title());
    }

    @Test
    public void aFailedFetchIsUnavailableAndNotAnEmptyChannel() {
        GetTopicTreeUseCase.Result r =
                new GetTopicTreeUseCase(new FakeRepository(null)).execute(ROOT);

        assertTrue(r.isUnavailable());
        // The distinction the screen depends on: this must not read as "empty".
        assertFalse(r.isEmpty());
        assertNull(r.node());
        assertTrue(r.children().isEmpty());
        assertEquals("", r.title());
    }

    @Test
    public void aTopicWithNothingUnderItIsEmptyButAvailable() {
        TopicNode barren = TopicNode.of(MATH, "Coming soon", TopicNode.KIND_TOPIC,
                false, 0L, 0, null);

        GetTopicTreeUseCase.Result r =
                new GetTopicTreeUseCase(new FakeRepository(barren)).execute(MATH);

        assertFalse(r.isUnavailable());
        assertTrue(r.isEmpty());
        assertEquals("Coming soon", r.title());
    }

    @Test
    public void aBadNodeIdNeverReachesTheNetwork() {
        FakeRepository repo = new FakeRepository(leaf(MATH, "x", MB));
        GetTopicTreeUseCase useCase = new GetTopicTreeUseCase(repo);

        assertTrue(useCase.execute(null).isUnavailable());
        assertTrue(useCase.execute("").isUnavailable());
        assertTrue(useCase.execute("../../etc/passwd").isUnavailable());
        assertTrue(useCase.execute("abc").isUnavailable());

        assertEquals(0, repo.calls);
    }

    @Test
    public void aDashedIdIsNormalisedBeforeTheRequest() {
        FakeRepository repo = new FakeRepository(leaf(MATH, "x", MB));
        new GetTopicTreeUseCase(repo).execute("6277aa0c-4423-5435-acdc-8a9ed98f466b");

        assertEquals(MATH, repo.asked);
    }

    @Test
    public void anUnsizedSubtreeIsStillOfferedRatherThanHidden() {
        // A topic whose child set Studio paged: the size is unknown, but the topic
        // is real content and must remain selectable.
        TopicNode partial = TopicNode.of(MATH, "Big unit", TopicNode.KIND_TOPIC, false,
                0L, 900, Arrays.asList(leaf(VIDEO, "One", MB)), false);
        TopicNode root = TopicNode.of(ROOT, "Channel", TopicNode.KIND_TOPIC, false,
                0L, 901, Arrays.asList(partial));

        GetTopicTreeUseCase.Result r =
                new GetTopicTreeUseCase(new FakeRepository(root)).execute(ROOT);

        assertEquals(1, r.children().size());
        TopicNode shown = r.children().get(0);
        assertFalse(shown.hasSubtreeSize());
        assertEquals(900, shown.descendantCount());
    }
}
