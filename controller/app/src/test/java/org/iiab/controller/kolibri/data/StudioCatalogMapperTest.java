package org.iiab.controller.kolibri.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.iiab.controller.kolibri.domain.Channel;
import org.iiab.controller.kolibri.domain.TopicNode;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for {@link StudioCatalogMapper}. Every case here is a real shape
 * from Kolibri Studio's public API, captured from live responses. Pure JVM: no
 * network, no Android.
 */
public class StudioCatalogMapperTest {

    private static final String CH = "c150ea1d69495d37b5b0ac6f017e9bfb";
    private static final String NODE = "23a7dc9c73635cd2abbd3e8aab13c3ca";

    // ---- channels ----------------------------------------------------------

    @Test
    public void readsAChannelRow() throws Exception {
        JSONObject o = new JSONObject()
                .put("id", CH)
                .put("name", "3asafeer")
                .put("description", "stories")
                .put("author", "Asafeer")
                .put("version", 3)
                .put("lang_code", "ar")
                .put("lang_name", "العربية")
                .put("total_resource_count", 180)
                .put("published_size", 1234567)
                .put("root", NODE);

        Channel c = StudioCatalogMapper.channel(o);
        assertEquals(CH, c.id());
        assertEquals(3, c.version());
        assertEquals("3asafeer", c.name());
        assertEquals("ar", c.langCode());
        assertEquals("العربية", c.langName());
        assertEquals(180, c.totalResources());
        assertEquals(1234567L, c.publishedSize());
        assertEquals(NODE, c.rootNodeId());
    }

    @Test
    public void publishedSizeIsReadAsALongNotAnInt() throws Exception {
        // A large channel exceeds 2 GB. Read as an int it wraps to a negative or
        // truncated size, and the fit check silently passes on a device that has
        // nowhere near the room.
        long big = 9_876_543_210L;
        JSONObject o = new JSONObject().put("id", CH).put("published_size", big);
        assertEquals(big, StudioCatalogMapper.channel(o).publishedSize());
    }

    @Test
    public void aRowWithoutAUsableIdIsNullNotAnEmptyChannel() throws Exception {
        assertNull(StudioCatalogMapper.channel(new JSONObject().put("id", "nope")));
        assertNull(StudioCatalogMapper.channel(new JSONObject()));
        assertNull(StudioCatalogMapper.channel(null));
    }

    @Test
    public void whenRootIsMissingItFallsBackToTheChannelId() throws Exception {
        // Studio reports root == id for most channels and omits it for some.
        // Losing it would mean losing the ability to browse that channel's tree.
        JSONObject o = new JSONObject().put("id", CH).put("root", "");
        assertEquals(CH, StudioCatalogMapper.channel(o).rootNodeId());
    }

    @Test
    public void readsAPageAndSkipsUnusableRows() throws Exception {
        JSONArray rows = new JSONArray()
                .put(new JSONObject().put("id", CH).put("name", "good"))
                .put(new JSONObject().put("id", "junk").put("name", "bad"))
                .put(JSONObject.NULL);
        JSONObject page = new JSONObject()
                .put("page", 1).put("count", 142).put("total_pages", 1)
                .put("results", rows);

        List<Channel> out = StudioCatalogMapper.channels(page);
        assertEquals(1, out.size());
        assertEquals("good", out.get(0).name());
        assertEquals(142, StudioCatalogMapper.totalCount(page));
        assertEquals(1, StudioCatalogMapper.totalPages(page));
    }

    @Test
    public void aBareArrayIsAcceptedToo() throws Exception {
        // The v1 endpoint answers with an unpaginated array; a caller pointed at
        // the wrong version should degrade rather than crash.
        JSONArray rows = new JSONArray().put(new JSONObject().put("id", CH));
        assertEquals(1, StudioCatalogMapper.channelArray(rows).size());
    }

    @Test
    public void aMissingPageIsEmptyNotAnError() {
        assertTrue(StudioCatalogMapper.channels(null).isEmpty());
        assertEquals(-1, StudioCatalogMapper.totalCount(null));
    }

    // ---- files and sizes ---------------------------------------------------

    @Test
    public void thumbnailFilesAreExcludedFromANodesSize() throws Exception {
        // Thumbnails are only fetched with all_thumbnails, so counting them
        // over-states a normal import. Both markers appear in live payloads.
        JSONArray files = new JSONArray()
                .put(new JSONObject().put("file_size", 1000).put("preset", "high_res_video"))
                .put(new JSONObject().put("file_size", 500).put("thumbnail", true))
                .put(new JSONObject().put("file_size", 300).put("preset", "topic_thumbnail"));
        assertEquals(1000L, StudioCatalogMapper.ownBytes(files));
    }

    @Test
    public void missingOrNegativeFileSizesDoNotCorruptTheTotal() throws Exception {
        JSONArray files = new JSONArray()
                .put(new JSONObject().put("file_size", 100))
                .put(new JSONObject())
                .put(new JSONObject().put("file_size", -50));
        assertEquals(100L, StudioCatalogMapper.ownBytes(files));
        assertEquals(0L, StudioCatalogMapper.ownBytes(null));
    }

    @Test
    public void descendantCountComesFromTheNestedSetBounds() throws Exception {
        // A node spanning [1, 360] holds (360 - 1 - 1) / 2 = 179 descendants,
        // which is a free answer to "how big is this topic?" with no extra fetch.
        assertEquals(179, StudioCatalogMapper.descendantCount(
                new JSONObject().put("lft", 1).put("rght", 360)));
        assertEquals(0, StudioCatalogMapper.descendantCount(
                new JSONObject().put("lft", 5).put("rght", 6)));
        assertEquals(0, StudioCatalogMapper.descendantCount(new JSONObject()));
        assertEquals(0, StudioCatalogMapper.descendantCount(
                new JSONObject().put("lft", 9).put("rght", 2)));
    }

    // ---- trees -------------------------------------------------------------

    @Test
    public void readsATreeWithNestedChildren() throws Exception {
        JSONObject child = new JSONObject()
                .put("id", NODE).put("title", "Beginner").put("kind", "video")
                .put("is_leaf", true)
                .put("files", new JSONArray().put(new JSONObject().put("file_size", 800)));
        JSONObject root = new JSONObject()
                .put("id", CH).put("title", "3asafeer").put("kind", "topic")
                .put("is_leaf", false).put("lft", 1).put("rght", 360)
                .put("children", new JSONObject()
                        .put("results", new JSONArray().put(child))
                        .put("more", JSONObject.NULL));

        TopicNode t = StudioCatalogMapper.tree(root);
        assertEquals(CH, t.id());
        assertTrue(t.isTopic());
        assertEquals(179, t.descendantCount());
        assertEquals(1, t.children().size());
        assertTrue(t.hasSubtreeSize());
        assertEquals(800L, t.subtreeBytes());
    }

    @Test
    public void aPagedChildLevelYieldsAnUnknownSubtreeSize() throws Exception {
        // children.more is the cursor. Non-null means more children exist, so the
        // arrived ones do not add up to the subtree.
        JSONObject root = new JSONObject()
                .put("id", CH).put("kind", "topic").put("is_leaf", false)
                .put("children", new JSONObject()
                        .put("results", new JSONArray().put(new JSONObject()
                                .put("id", NODE).put("is_leaf", true)
                                .put("files", new JSONArray()
                                        .put(new JSONObject().put("file_size", 10)))))
                        .put("more", new JSONObject().put("cursor", "abc")));

        TopicNode t = StudioCatalogMapper.tree(root);
        assertEquals(1, t.children().size());
        assertFalse(t.hasSubtreeSize());
    }

    @Test
    public void aNodeWithNoChildrenBlockIsALeafShapedRead() throws Exception {
        JSONObject leaf = new JSONObject()
                .put("id", NODE).put("kind", "document").put("is_leaf", true)
                .put("files", new JSONArray().put(new JSONObject().put("file_size", 42)));
        TopicNode t = StudioCatalogMapper.tree(leaf);
        assertTrue(t.isLeaf());
        assertTrue(t.hasSubtreeSize());
        assertEquals(42L, t.subtreeBytes());
    }

    @Test
    public void aTreeWithoutAUsableIdIsNull() throws Exception {
        assertNull(StudioCatalogMapper.tree(new JSONObject().put("id", "nope")));
        assertNull(StudioCatalogMapper.tree(null));
    }
}
