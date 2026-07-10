/*
 * ============================================================================
 * Name        : ServerLogView.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4640 — server-log console with terminal-style scrolling:
 *               sticky read position, follow/tail (less +F), a jump-to-newest
 *               affordance, a grabbable custom scrollbar (tap-to-jump + drag),
 *               and a bounded buffer. Uses a custom scrollbar instead of the
 *               AndroidX fast-scroller (which was not tap-jumpable, rendered a
 *               second bar next to the native one, and was hard to grab).
 * ============================================================================
 */
package org.iiab.controller;

import android.annotation.SuppressLint;
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

public class ServerLogView extends FrameLayout {

    /** Keep at most this many lines in memory. */
    private static final int MAX_LINES = 8000;

    private RecyclerView recycler;
    private LinearLayoutManager layoutManager;
    private LogAdapter adapter;
    private View jumpLatest;
    private View scrollbar;
    private View thumb;
    private boolean following = true;

    public ServerLogView(@NonNull Context context) { this(context, null); }

    public ServerLogView(@NonNull Context context, @Nullable AttributeSet attrs) { this(context, attrs, 0); }

    @SuppressLint("ClickableViewAccessibility")
    public ServerLogView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        LayoutInflater.from(context).inflate(R.layout.view_server_log, this, true);
        recycler = findViewById(R.id.log_recycler);
        jumpLatest = findViewById(R.id.log_jump_latest);
        scrollbar = findViewById(R.id.log_scrollbar);
        thumb = findViewById(R.id.log_scroll_thumb);

        layoutManager = new LinearLayoutManager(context);
        layoutManager.setStackFromEnd(true); // pin content to the bottom (newest) like a log
        recycler.setLayoutManager(layoutManager);
        adapter = new LogAdapter();
        recycler.setAdapter(adapter);

        // Own the vertical drag while nested in the page ScrollView.
        recycler.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                    disallowParentIntercept(true);
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    disallowParentIntercept(false);
                }
                return false;
            }
        });

        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                // Only a user-driven scroll (drag/fling) changes follow state; programmatic
                // scrolls (append tail, jump) are instant (IDLE) and must not detach.
                if (rv.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
                    following = isAtBottom();
                    jumpLatest.setVisibility(following ? GONE : VISIBLE);
                }
                updateThumb();
            }
        });

        // Grabbable scrollbar: tap anywhere to jump there, drag to move across thousands of lines.
        scrollbar.setOnTouchListener((v, e) -> {
            int trackH = scrollbar.getHeight();
            int count = adapter.getItemCount();
            if (trackH <= 0 || count == 0) return false;
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                disallowParentIntercept(true);
                float frac = clamp01(e.getY() / trackH);
                int target = Math.round(frac * (count - 1));
                layoutManager.scrollToPositionWithOffset(target, 0);
                following = frac >= 0.995f;
                jumpLatest.setVisibility(following ? GONE : VISIBLE);
                recycler.post(this::updateThumb);
                return true;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                disallowParentIntercept(false);
                following = isAtBottom();
                jumpLatest.setVisibility(following ? GONE : VISIBLE);
                return true;
            }
            return false;
        });

        jumpLatest.setOnClickListener(v -> {
            following = true;
            scrollToEnd();
            jumpLatest.setVisibility(GONE);
        });
        jumpLatest.setVisibility(GONE);
    }

    private void disallowParentIntercept(boolean disallow) {
        ViewGroup p = (ViewGroup) getParent();
        if (p != null) p.requestDisallowInterceptTouchEvent(disallow);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** At the bottom when the list can no longer scroll further down. */
    private boolean isAtBottom() {
        return !recycler.canScrollVertically(1);
    }

    private void scrollToEnd() {
        int n = adapter.getItemCount();
        if (n > 0) {
            recycler.scrollToPosition(n - 1);
            recycler.post(this::updateThumb);
        }
    }

    /** Position/size the custom scrollbar thumb from the RecyclerView scroll metrics. */
    private void updateThumb() {
        int trackH = scrollbar.getHeight();
        if (trackH <= 0) return;
        int range = recycler.computeVerticalScrollRange();
        int extent = recycler.computeVerticalScrollExtent();
        int offset = recycler.computeVerticalScrollOffset();
        if (adapter.getItemCount() == 0 || range <= extent) {
            scrollbar.setVisibility(GONE);
            return;
        }
        scrollbar.setVisibility(VISIBLE);
        int minThumb = Math.round(40f * getResources().getDisplayMetrics().density);
        int thumbH = (int) ((extent / (float) range) * trackH);
        if (thumbH < minThumb) thumbH = minThumb;
        if (thumbH > trackH) thumbH = trackH;
        ViewGroup.LayoutParams lp = thumb.getLayoutParams();
        if (lp.height != thumbH) {
            lp.height = thumbH;
            thumb.setLayoutParams(lp);
        }
        float denom = range - extent;
        float frac = denom > 0 ? offset / denom : 0f;
        thumb.setTranslationY(clamp01(frac) * (trackH - thumbH));
    }

    /** Append one line (no trailing newline). Auto-scrolls only while following the tail. */
    public void append(String line) {
        if (line == null) return;
        adapter.add(line);
        if (following) {
            scrollToEnd();
        } else {
            jumpLatest.setVisibility(VISIBLE);
            recycler.post(this::updateThumb);
        }
    }

    /** Replace all content from a newline-delimited string; sticks to the end. */
    public void setContent(String content) {
        List<String> lines = new ArrayList<>();
        if (content != null && !content.isEmpty()) {
            String[] parts = content.split("\n", -1);
            for (String p : parts) lines.add(p);
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
        recycler.post(this::updateThumb);
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
