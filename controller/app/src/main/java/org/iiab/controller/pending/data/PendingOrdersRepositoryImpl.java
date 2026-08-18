/*
 * ============================================================================
 * Name        : PendingOrdersRepositoryImpl.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Reads the three live content wishlists (ZIM, Books, Courses) into
 *               PendingOrders and cancels one by removing it from its wishlist.
 *               The single place that maps a content type to its wishlist for the
 *               pending list, alongside PendingContent's existing knowledge
 *               (ADFA-5169). Maps and modules are out of scope by design.
 * ============================================================================
 */
package org.iiab.controller.pending.data;

import android.content.Context;

import org.iiab.controller.kolibri.data.KolibriWishlist;
import org.iiab.controller.pending.domain.PendingOrder;
import org.iiab.controller.pending.domain.PendingOrdersRepository;
import org.iiab.controller.redesign.BooksWishlist;
import org.iiab.controller.redesign.ZimWishlist;
import org.iiab.controller.system.domain.ContentType;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PendingOrdersRepositoryImpl implements PendingOrdersRepository {

    private final Context app;

    public PendingOrdersRepositoryImpl(Context ctx) {
        this.app = ctx.getApplicationContext();
    }

    @Override
    public List<PendingOrder> list() {
        List<PendingOrder> out = new ArrayList<>();
        addZim(out);
        addBooks(out);
        addCourses(out);
        Collections.sort(out, PendingOrder.DISPLAY_ORDER);
        return out;
    }

    @Override
    public void cancel(PendingOrder order) {
        if (order == null || order.type() == null || order.id() == null) {
            return;
        }
        switch (order.type()) {
            case ZIM:     ZimWishlist.remove(app, order.id()); break;
            case BOOKS:   BooksWishlist.remove(app, order.id()); break;
            case COURSES: KolibriWishlist.remove(app, order.id()); break;
            default: break;   // MAPS is out of scope: it keeps its own cancel path.
        }
    }

    /** ZIM order = {@code {key, bytes}}; the key ("project|lang|flavour") is both id
     *  and, absent a friendlier catalog name here, the display name. */
    private void addZim(List<PendingOrder> out) {
        JSONArray a = ZimWishlist.all(app);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String key = o.optString("key", "");
            if (key.isEmpty()) continue;
            out.add(new PendingOrder(ContentType.ZIM, key, key, o.optLong("bytes", 0L)));
        }
    }

    /** Books order = {@code {id, title, url}}; no size is stored, so bytes is 0 (unknown). */
    private void addBooks(List<PendingOrder> out) {
        JSONArray a = BooksWishlist.all(app);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "");
            if (id.isEmpty()) continue;
            String title = o.optString("title", "");
            out.add(new PendingOrder(ContentType.BOOKS, id, title.isEmpty() ? id : title, 0L));
        }
    }

    /** Courses order = {@code {channelId, version, name, bytes}}. */
    private void addCourses(List<PendingOrder> out) {
        JSONArray a = KolibriWishlist.all(app);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("channelId", "");
            if (id.isEmpty()) continue;
            String name = o.optString("name", "");
            out.add(new PendingOrder(ContentType.COURSES, id, name.isEmpty() ? id : name, o.optLong("bytes", 0L)));
        }
    }
}
