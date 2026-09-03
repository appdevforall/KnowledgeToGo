package org.appdevforall.k2go.kolibri.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Unit tests for {@link GetChannelCatalogUseCase} against a fake repository.
 * Pure JVM: no Android, no asset, no network.
 */
public class GetChannelCatalogUseCaseTest {

    private static final String KHAN = "95a52b386f2c485cb97dd60901674a98";
    private static final String ASAF = "c150ea1d69495d37b5b0ac6f017e9bfb";
    private static final String STORY = "f9d3e0e46ea25789bbed672ff6a399ed";

    /** Minimal stand-in; the tree side is unused by these tests. */
    private static final class FakeRepo implements CatalogRepository {
        private final List<Channel> rows;
        private final String generated;
        int channelReads;

        FakeRepo(List<Channel> rows, String generated) {
            this.rows = rows;
            this.generated = generated;
        }

        @Override
        public List<Channel> channels() {
            channelReads++;
            return rows;
        }

        @Override
        public String catalogGeneratedOn() {
            return generated;
        }

        @Override
        public TopicNode fetchTree(String nodeId) {
            throw new UnsupportedOperationException("not used here");
        }
    }

    private static Channel ch(String id, int version, String name, String lang, long bytes) {
        return Channel.builder(id).version(version).name(name)
                .language(lang, lang).publishedSize(bytes).build();
    }

    @Test
    public void returnsEveryChannelWhenUnfiltered() {
        FakeRepo repo = new FakeRepo(Arrays.asList(
                ch(KHAN, 1, "Khan Academy", "en", 10L),
                ch(ASAF, 1, "3asafeer", "ar", 20L)), "2026-08-05");
        GetChannelCatalogUseCase.Result r = new GetChannelCatalogUseCase(repo).execute();
        assertEquals(2, r.size());
        assertEquals("2026-08-05", r.generatedOn());
        assertTrue(r.hasGeneratedOn());
    }

    @Test
    public void filtersByLanguage() {
        FakeRepo repo = new FakeRepo(Arrays.asList(
                ch(KHAN, 1, "Khan Academy", "en", 10L),
                ch(ASAF, 1, "3asafeer", "ar", 20L)), "");
        GetChannelCatalogUseCase.Result r = new GetChannelCatalogUseCase(repo)
                .execute(CatalogQuery.ofLanguage("ar"));
        assertEquals(1, r.size());
        assertEquals("3asafeer", r.channels().get(0).name());
    }

    @Test
    public void filtersByKeywordAcrossNameAndDescription() {
        Channel withDesc = Channel.builder(STORY).name("Library")
                .description("African storybooks for early readers").build();
        FakeRepo repo = new FakeRepo(Arrays.asList(
                ch(KHAN, 1, "Khan Academy", "en", 10L), withDesc), "");
        GetChannelCatalogUseCase uc = new GetChannelCatalogUseCase(repo);

        assertEquals(1, uc.execute(CatalogQuery.of("khan", null)).size());
        assertEquals(1, uc.execute(CatalogQuery.of("storybooks", null)).size());
        assertEquals(0, uc.execute(CatalogQuery.of("nothing here", null)).size());
    }

    @Test
    public void aDuplicateIdIsShownOnce() {
        // The generator already collapses these, but a hand-edited asset or a
        // future source could reintroduce them, and two identical rows in a
        // picker is a bug the user sees.
        FakeRepo repo = new FakeRepo(Arrays.asList(
                ch(KHAN, 2, "Khan Academy", "en", 10L),
                ch(KHAN, 1, "Khan Academy (old)", "en", 5L)), "");
        GetChannelCatalogUseCase.Result r = new GetChannelCatalogUseCase(repo).execute();
        assertEquals(1, r.size());
        assertEquals("Khan Academy", r.channels().get(0).name());
    }

    @Test
    public void nullRowsAndANullListAreSurvivable() {
        List<Channel> withNull = new ArrayList<>();
        withNull.add(ch(KHAN, 1, "Khan Academy", "en", 10L));
        withNull.add(null);
        assertEquals(1, new GetChannelCatalogUseCase(new FakeRepo(withNull, "")).execute().size());

        assertTrue(new GetChannelCatalogUseCase(new FakeRepo(null, null))
                .execute().isEmpty());
    }

    @Test
    public void anAbsentGeneratedStampIsEmptyNotNull() {
        GetChannelCatalogUseCase.Result r = new GetChannelCatalogUseCase(
                new FakeRepo(Collections.<Channel>emptyList(), null)).execute();
        assertEquals("", r.generatedOn());
        assertFalse(r.hasGeneratedOn());
    }

    @Test
    public void aNullQueryMeansUnfilteredRatherThanNothing() {
        FakeRepo repo = new FakeRepo(Collections.singletonList(
                ch(KHAN, 1, "Khan Academy", "en", 10L)), "");
        assertEquals(1, new GetChannelCatalogUseCase(repo).execute(null).size());
    }

    @Test
    public void availableLanguagesAreDerivedFromTheCatalogNotHardcoded() {
        FakeRepo repo = new FakeRepo(Arrays.asList(
                ch(KHAN, 1, "Khan Academy", "en", 10L),
                ch(ASAF, 1, "3asafeer", "ar", 20L),
                ch(STORY, 1, "Storybooks", "en", 30L)), "");
        assertEquals(Arrays.asList("en", "ar"),
                new GetChannelCatalogUseCase(repo).availableLanguages());
    }

    @Test
    public void resultsAreNotMutableFromOutside() {
        FakeRepo repo = new FakeRepo(Collections.singletonList(
                ch(KHAN, 1, "Khan Academy", "en", 10L)), "");
        try {
            new GetChannelCatalogUseCase(repo).execute().channels()
                    .add(ch(ASAF, 1, "x", "ar", 1L));
            fail("expected the result list to be immutable");
        } catch (UnsupportedOperationException expected) {
            // as designed
        }
    }
}
