/*
 * ============================================================================
 * Name        : ServerLogView.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4640 — server-log console with terminal-style scrolling:
 *               sticky read position, follow/tail (less +F), a jump-to-newest
 *               affordance, a draggable fast-scroll thumb, and a bounded buffer.
 * ============================================================================
 */
package org.iiab.controller;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * A read-only log console backed by a {@link RecyclerView}.
 *
 * <p>Behaviors (ADFA-4640):
 * <ul>
 *   <li><b>Sticky read position:</b> new lines never move the viewport unless you are
 *       following the tail, so you can stop and read while the log keeps growing.</li>
 *   <li><b>Follow / tail (like {@code less +F}):</b> when the viewport is at the bottom the
 *       console auto-sticks to the newest line; scrolling up detaches, and the
 *       jump-to-newest button re-attaches.</li>
 *   <li><b>Draggable fast-scroll thumb</b> (configured in the XML) to move start&lt;-&gt;end.</li>
 *   <li><b>Bounded ring buffer</b> ({@link #MAX_LINES}) so long streams stay smooth.</li>
 * </ul>
 */
public class ServerLogView extends FrameLayout {

    /** Keep at most this many lines in memory. */
    private static final int MAX_LINES = 8000;

    private RecyclerView recycler;
    private LinearLayoutManager layoutManager;
    private LogAdapter adapter;
    private View jumpLatest;
    private boolean following = true;

    public ServerLogView(@NonNull Context context) { this(context, null); }

    public ServerLogView(@NonNull Context context, @Nullable AttributeSet attrs) { this(context, attrs, 0); }

    public ServerLogView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        LayoutInflater.from(context).inflate(R.layout.view_server_log, this, true);
        recycler = findViewById(R.id.log_recycler);
        jumpLatest = findViewById(R.id.log_jump_latest);

        layoutManager = new LinearLayoutManager(context);
        layoutManager.setStackFromEnd(true); // pin content to the bottom (newest) like a log
        recycler.setLayoutManager(layoutManager);
        adapter = new LogAdapter();
        recycler.setAdapter(adapter);

        // Own the vertical drag while nested in the page ScrollView (incl. fast-scroll thumb).
        recycler.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                    rv.getParent().requestDisallowInterceptTouchEvent(true);
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    rv.getParent().requestDisallowInterceptTouchEvent(false);
                }
                return false;
            }
        });

        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                following = isAtBottom();
                jumpLatest.setVisibility(following ? GONE : VISIBLE);
            }
        });

        jumpLatest.setOnClickListener(v -> {
            following = true;
            scrollToEnd();
            jumpLatest.setVisibility(GONE);
        });
        jumpLatest.setVisibility(GONE);
    }

    private boolean isAtBottom() {
        int last = layoutManager.findLastVisibleItemPosition();
        return last == RecyclerView.NO_POSITION || last >= adapter.getItemCount() - 1;
    }

    private void scrollToEnd() {
        int n = adapter.getItemCount();
        if (n > 0) recycler.post(() -> recycler.scrollToPosition(n - 1));
    }

    /** Append one line (no trailing newline). Auto-scrolls only while following the tail. */
    public void append(String line) {
        if (line == null) return;
        adapter.add(line);
        if (following) {
            scrollToEnd();
        } else {
            jumpLatest.setVisibility(VISIBLE);
        }
    }

    /** Replace all content from a newline-delimited string (e.g. history load); sticks to the end. */
    public void setContent(String content) {
        List<String> lines = new ArrayList<>();
        if (content != null && !content.isEmpty()) {
            String[] parts = content.split("\n", -1);
            for (String p : parts) lines.add(p);
            // drop the single empty token produced by a trailing '\n'
            if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                lines.remove(lines.size() - 1);
            }
        }
        adapter.setAll(lines);
        following = true;
        scrollToEnd();
        jumpLatest.setVisibility(GONE);
    }

    /** Clear all lines. */
    public void clear() {
        adapter.setAll(new ArrayList<>());
        following = true;
        jumpLatest.setVisibility(GONE);
    }

    /** All current lines joined with '\n' (for copy-to-clipboard). */
    public String getContent() {
        return TextUtils.join("\n", adapter.lines);
    }

    // ---------------------------------------------------------------------

    private static final class LogAdapter extends RecyclerView.Adapter<LogAdapter.Holder> {

        final ArrayList<String> lines = new ArrayList<>();

        void add(String line) {
            lines.add(line);
            int over = lines.size() - MAX_LINES;
            if (over > 0) {
                lines.subList(0, over).clear();
                notifyDataSetChanged();
            } else {
                notifyItemInserted(lines.size() - 1);
            }
        }

        void setAll(List<String> newLines) {
            lines.clear();
            lines.addAll(newLines);
            if (lines.size() > MAX_LINES) {
                lines.subList(0, lines.size() - MAX_LINES).clear();
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = (TextView) LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_log_line, parent, false);
            return new Holder(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.text.setText(lines.get(position));
        }

        @Override
        public int getItemCount() {
            return lines.size();
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final TextView text;
            Holder(@NonNull TextView itemView) {
                super(itemView);
                this.text = itemView;
            }
        }
    }
}
