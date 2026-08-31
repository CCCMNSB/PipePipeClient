package org.schabi.newpipe.subtitles;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.chip.Chip;
import com.google.android.material.tabs.TabLayout;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.OnClickGesture;
import org.schabi.newpipe.util.ThemeHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A left-navigation-drawer list screen showing every video that has an online subtitle published
 * in the subtitle manifest ({@code index.json}, newest first).
 *
 * <p>Channel-style layout: a top {@link TabLayout} with two tabs — "videos" (every entry, with a
 * search box that filters by title/id, batched loading and tap-to-open) and "collections" (合集,
 * derived client-side from the optional {@code list} field of the manifest). Tapping a collection
 * jumps back to the videos tab with a filter chip showing the collection name; the chip can be
 * cleared again at any time. If no manifest entry carries a {@code list} field, the tab bar stays
 * hidden and the screen behaves exactly like the original single list.</p>
 */
public class SubtitleJumpFragment extends Fragment {

    private static final int BATCH_SIZE = 50;
    /** Minimum interval between manual refreshes (client-side throttle). */
    private static final long REFRESH_MIN_INTERVAL_MS = 30 * 1000L;

    private static final int MODE_VIDEO = 0;
    private static final int MODE_COLLECTION = 1;

    private RecyclerView listView;
    private ProgressBar loadingView;
    private TextView messageView;
    private android.widget.EditText searchView;
    private android.widget.Button loadMoreButton;
    private ImageButton clearCacheButton;
    private ImageButton refreshButton;
    private SwipeRefreshLayout swipeRefresh;
    private TabLayout tabLayout;
    private View filterRow;
    private Chip filterChip;
    private ImageButton filterClearButton;
    private SubtitleRecyclerAdapter adapter;
    private SubtitleCollectionAdapter collectionAdapter;

    private ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** Debounced search recompute: runs 150ms after the last keystroke. */
    private final Runnable searchDebounce = this::recompute;

    private List<SubtitleVideoItem> allItems = new ArrayList<>();
    private List<SubtitleVideoItem> filteredItems = new ArrayList<>();
    private List<SubtitleCollection> allCollections = new ArrayList<>();
    private List<SubtitleCollection> filteredCollections = new ArrayList<>();
    private int displayCount = BATCH_SIZE;
    private boolean loaded = false;
    private String lastIndexUrl = null;
    private long lastRefreshMs = 0;
    private int currentMode = MODE_VIDEO;
    /** Active collection filter: null = no filter, "" = uncategorized, otherwise the collection name. */
    private String activeCollection = null;

    private final TextWatcher searchWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(final CharSequence s, final int start, final int count,
                                       final int after) {
        }

        @Override
        public void onTextChanged(final CharSequence s, final int start, final int before,
                                   final int count) {
            // 防抖：停止输入 150ms 后才重算，避免每敲一个字就全量过滤
            mainHandler.removeCallbacks(searchDebounce);
            mainHandler.postDelayed(searchDebounce, 150);
        }

        @Override
        public void afterTextChanged(final Editable s) {
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_subtitle_jump, container, false);
    }

    @Override
    public void onViewCreated(@NonNull final View view,
                              @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        listView = view.findViewById(R.id.subtitle_jump_list);
        loadingView = view.findViewById(R.id.subtitle_jump_loading);
        messageView = view.findViewById(R.id.subtitle_jump_message);
        searchView = view.findViewById(R.id.subtitle_jump_search);
        loadMoreButton = view.findViewById(R.id.subtitle_jump_load_more);
        clearCacheButton = view.findViewById(R.id.subtitle_jump_clear_cache);
        if (clearCacheButton != null) {
            clearCacheButton.setOnClickListener(v -> {
                org.schabi.newpipe.player.subtitles.SubtitleCache.clearAll(getContext());
                android.widget.Toast.makeText(getContext(), getString(R.string.subtitle_cache_manage_empty),
                        android.widget.Toast.LENGTH_SHORT).show();
                loadRepo(false);
            });
        }
        refreshButton = view.findViewById(R.id.subtitle_jump_refresh);
        if (refreshButton != null) {
            refreshButton.setOnClickListener(v -> {
                final long now = System.currentTimeMillis();
                if (now - lastRefreshMs < REFRESH_MIN_INTERVAL_MS) {
                    android.widget.Toast.makeText(getContext(),
                            getString(R.string.subtitle_jump_refresh_too_soon),
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                lastRefreshMs = now;
                loadRepo(true);
            });
        }
        swipeRefresh = view.findViewById(R.id.subtitle_jump_swipe);
        if (swipeRefresh != null) {
            swipeRefresh.setOnRefreshListener(() -> loadRepo(true));
        }

        // Channel-style top tabs: 视频 / 合集.
        tabLayout = view.findViewById(R.id.subtitle_jump_tabs);
        if (tabLayout != null) {
            tabLayout.addTab(tabLayout.newTab().setText(R.string.subtitle_jump_tab_videos));
            tabLayout.addTab(tabLayout.newTab().setText(R.string.subtitle_jump_tab_collections));
            tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override
                public void onTabSelected(final TabLayout.Tab tab) {
                    switchToMode(tab.getPosition() == 0 ? MODE_VIDEO : MODE_COLLECTION);
                }

                @Override
                public void onTabUnselected(final TabLayout.Tab tab) {
                }

                @Override
                public void onTabReselected(final TabLayout.Tab tab) {
                }
            });
        }

        // Active collection filter chip (video tab only).
        filterRow = view.findViewById(R.id.subtitle_jump_filter_row);
        filterChip = view.findViewById(R.id.subtitle_jump_filter_chip);
        filterClearButton = view.findViewById(R.id.subtitle_jump_filter_clear);
        if (filterClearButton != null) {
            filterClearButton.setOnClickListener(v -> clearCollectionFilter());
        }
        if (filterChip != null) {
            filterChip.setOnClickListener(v -> clearCollectionFilter());
        }

        adapter = new SubtitleRecyclerAdapter();
        adapter.setOnItemClickListener(new OnClickGesture<SubtitleVideoItem>() {
            @Override
            public void selected(final SubtitleVideoItem item) {
                onItemSelected(item);
            }
        });
        collectionAdapter = new SubtitleCollectionAdapter();
        collectionAdapter.setOnItemClickListener(new OnClickGesture<SubtitleCollection>() {
            @Override
            public void selected(final SubtitleCollection collection) {
                onCollectionSelected(collection);
            }
        });
        // Channel-style 2-column grid (same span count as a channel's video tab).
        listView.setLayoutManager(new GridLayoutManager(getContext(),
                ThemeHelper.getGridSpanCountStreams(getContext())));
        listView.setAdapter(adapter);

        searchView.addTextChangedListener(searchWatcher);
        // Infinite scroll: load the next batch as the user reaches the end (videos tab only).
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(final androidx.recyclerview.widget.RecyclerView recyclerView,
                                    final int dx, final int dy) {
                if (currentMode != MODE_VIDEO || dy <= 0) {
                    return;
                }
                final LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (lm == null) {
                    return;
                }
                final int last = lm.findLastVisibleItemPosition();
                final int total = recyclerView.getAdapter() != null
                        ? recyclerView.getAdapter().getItemCount() : 0;
                if (last >= total - 1) {
                    tryLoadMore();
                }
            }
        });

        loadRepo(false);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof AppCompatActivity) {
            final ActionBar actionBar =
                    ((AppCompatActivity) getActivity()).getSupportActionBar();
            if (actionBar != null) {
                actionBar.setTitle(R.string.tab_subtitles);
            }
        }
        // If the user switched the subtitle repository while this screen was in the background,
        // reload so the list now reflects the new repository.
        if (loaded && lastIndexUrl != null) {
            final String cur = org.schabi.newpipe.subtitles.SubtitleRepoFetcher.currentIndexUrl(getContext());
            if (!lastIndexUrl.equals(cur)) {
                loadRepo(true);
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (searchView != null) {
            searchView.removeTextChangedListener(searchWatcher);
        }
        mainHandler.removeCallbacks(searchDebounce);
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        listView = null;
        loadingView = null;
        messageView = null;
        searchView = null;
        loadMoreButton = null;
        clearCacheButton = null;
        refreshButton = null;
        swipeRefresh = null;
        tabLayout = null;
        filterRow = null;
        filterChip = null;
        filterClearButton = null;
        adapter = null;
        collectionAdapter = null;
    }

    private void onItemSelected(final SubtitleVideoItem item) {
        // Set the pending auto-load id BEFORE opening the video, so the player can pick it up once
        // the stream is prepared.
        Player.setPendingAutoLoadSubtitleId(item.videoId);
        // BV(B站) 与 YouTube 是两套机制：按 id 智能切换服务，避免把 B站 视频按 YouTube 打开。
        final int serviceId = SubtitleRepoFetcher.isBilibiliId(item.videoId)
                ? ServiceList.BiliBili.getServiceId()
                : ServiceList.YouTube.getServiceId();
        NavigationHelper.openVideoDetailFragment(
                requireContext(),
                requireActivity().getSupportFragmentManager(),
                serviceId,
                SubtitleRepoFetcher.watchUrlFor(item.videoId),
                item.title == null ? item.videoId : item.title,
                null,
                false);
    }

    private void onCollectionSelected(final SubtitleCollection collection) {
        activeCollection = collection.name;
        // Jump back to the videos tab with the collection filter applied.
        if (tabLayout != null && tabLayout.getVisibility() == View.VISIBLE) {
            tabLayout.selectTab(tabLayout.getTabAt(0));
        }
        switchToMode(MODE_VIDEO);
    }

    private void clearCollectionFilter() {
        if (activeCollection == null) {
            return;
        }
        activeCollection = null;
        updateFilterChip();
        if (currentMode == MODE_VIDEO) {
            recompute();
        }
    }

    /** Switch the visible list to the videos tab or the collections tab. */
    private void switchToMode(final int mode) {
        if (listView == null) {
            return;
        }
        currentMode = mode;
        if (mode == MODE_COLLECTION) {
            listView.setAdapter(collectionAdapter);
            // The filter chip belongs to the videos tab.
            if (filterRow != null) {
                filterRow.setVisibility(View.GONE);
            }
        } else {
            listView.setAdapter(adapter);
            updateFilterChip();
        }
        recompute();
    }

    private void updateFilterChip() {
        if (filterRow == null) {
            return;
        }
        if (activeCollection == null) {
            filterRow.setVisibility(View.GONE);
            return;
        }
        filterRow.setVisibility(View.VISIBLE);
        if (filterChip != null) {
            filterChip.setText(activeCollection.isEmpty()
                    ? getString(R.string.subtitle_collection_uncategorized)
                    : activeCollection);
        }
    }

    private boolean matchesActiveCollection(final SubtitleVideoItem item) {
        if (activeCollection == null) {
            return true;
        }
        if (activeCollection.isEmpty()) {
            return item.listName == null;
        }
        return item.listName != null && item.listName.equalsIgnoreCase(activeCollection);
    }

    private boolean collectionExists(final String name) {
        for (final SubtitleCollection c : allCollections) {
            if (c.name != null && c.name.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Loading
    ///////////////////////////////////////////////////////////////////////////

    private void loadRepo(final boolean force) {
        showLoading();
        executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            List<SubtitleVideoItem> result = new ArrayList<>();
            boolean failed = false;
            try {
                result = SubtitleRepoFetcher.fetchRepoSubtitles(getContext(), force);
            } catch (final Exception e) {
                failed = true;
            }
            final List<SubtitleVideoItem> finalResult = result;
            final boolean finalFailed = failed;
            mainHandler.post(() -> {
                if (!isAdded()) {
                    return;
                }
                if (swipeRefresh != null) {
                    swipeRefresh.setRefreshing(false);
                }
                if (finalFailed) {
                    loaded = true;
                    allItems = new ArrayList<>();
                    filteredItems = new ArrayList<>();
                    allCollections = new ArrayList<>();
                    filteredCollections = new ArrayList<>();
                    showMessage(getString(R.string.subtitle_jump_empty));
                    return;
                }
                loaded = true;
                lastIndexUrl = org.schabi.newpipe.subtitles.SubtitleRepoFetcher.currentIndexUrl(getContext());
                allItems = finalResult;
                allCollections = SubtitleCollection.groupByCollection(finalResult);
                // Drop a stale collection filter (e.g. after a repository switch).
                if (activeCollection != null && !collectionExists(activeCollection)) {
                    activeCollection = null;
                }
                boolean hasCollections = false;
                for (final SubtitleCollection c : allCollections) {
                    if (c.name != null && !c.name.isEmpty()) {
                        hasCollections = true;
                        break;
                    }
                }
                if (tabLayout != null) {
                    tabLayout.setVisibility(hasCollections ? View.VISIBLE : View.GONE);
                }
                if (!hasCollections) {
                    currentMode = MODE_VIDEO;
                    if (listView != null) {
                        listView.setAdapter(adapter);
                    }
                    if (filterRow != null) {
                        filterRow.setVisibility(View.GONE);
                    }
                }
                recompute();
            });
        });
    }

    ///////////////////////////////////////////////////////////////////////////
    // Search + mode binding
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Re-derives both lists (videos and collections) from the current search query and collection
     * filter, then binds whichever one is visible.
     */
    private void recompute() {
        if (!loaded || searchView == null) {
            return;
        }
        final String query = searchView.getText() == null
                ? "" : searchView.getText().toString();
        final List<SubtitleVideoItem> vids = new ArrayList<>();
        for (final SubtitleVideoItem item : allItems) {
            if (item.matches(query) && matchesActiveCollection(item)) {
                vids.add(item);
            }
        }
        filteredItems = vids;
        final List<SubtitleCollection> cols = new ArrayList<>();
        for (final SubtitleCollection c : allCollections) {
            if (c.matches(query)) {
                cols.add(c);
            }
        }
        filteredCollections = cols;
        displayCount = BATCH_SIZE;
        bindCurrentMode();
    }

    private void bindCurrentMode() {
        final boolean empty;
        if (currentMode == MODE_COLLECTION) {
            empty = filteredCollections.isEmpty();
            if (collectionAdapter != null) {
                collectionAdapter.setItems(empty ? new ArrayList<>() : filteredCollections);
            }
        } else {
            empty = filteredItems.isEmpty();
            if (adapter != null) {
                final int end = Math.min(displayCount, filteredItems.size());
                adapter.setItems(new ArrayList<>(filteredItems.subList(0, end)));
            }
        }
        if (loadMoreButton != null) {
            loadMoreButton.setVisibility(View.GONE);
        }
        if (empty) {
            showMessage(getString(R.string.subtitle_jump_empty));
        } else {
            showList();
        }
    }

    private void tryLoadMore() {
        if (currentMode != MODE_VIDEO) {
            return;
        }
        if (displayCount >= filteredItems.size()) {
            return;
        }
        displayCount = Math.min(displayCount + BATCH_SIZE, filteredItems.size());
        bindCurrentMode();
    }

    ///////////////////////////////////////////////////////////////////////////
    // View states
    ///////////////////////////////////////////////////////////////////////////

    private void showLoading() {
        if (loadingView != null) {
            loadingView.setVisibility(View.VISIBLE);
        }
        if (listView != null) {
            listView.setVisibility(View.GONE);
        }
        if (messageView != null) {
            messageView.setVisibility(View.GONE);
        }
        if (loadMoreButton != null) {
            loadMoreButton.setVisibility(View.GONE);
        }
    }

    private void showList() {
        if (loadingView != null) {
            loadingView.setVisibility(View.GONE);
        }
        if (listView != null) {
            listView.setVisibility(View.VISIBLE);
        }
        if (messageView != null) {
            messageView.setVisibility(View.GONE);
        }
    }

    private void showMessage(final String message) {
        if (loadingView != null) {
            loadingView.setVisibility(View.GONE);
        }
        if (listView != null) {
            listView.setVisibility(View.GONE);
        }
        if (messageView != null) {
            messageView.setText(message);
            messageView.setVisibility(View.VISIBLE);
        }
        if (loadMoreButton != null) {
            loadMoreButton.setVisibility(View.GONE);
        }
    }
}
