package org.schabi.newpipe.subtitles;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.OnClickGesture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A left-navigation-drawer list screen showing every video that has an online subtitle published in
 * the subtitle manifest ({@code index.json}, newest first). Each row shows the thumbnail and title;
 * a search box filters by title/id, and the list loads in batches to stay responsive. Tapping a row
 * opens the video in the player and auto-loads its subtitle.
 */
public class SubtitleJumpFragment extends Fragment {

    private static final int BATCH_SIZE = 50;

    private RecyclerView listView;
    private ProgressBar loadingView;
    private TextView messageView;
    private EditText searchView;
    private Button loadMoreButton;
    private android.widget.ImageButton clearCacheButton;
    private SubtitleRecyclerAdapter adapter;

    private ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private List<SubtitleVideoItem> allItems = new ArrayList<>();
    private List<SubtitleVideoItem> filteredItems = new ArrayList<>();
    private int displayCount = BATCH_SIZE;
    private boolean loaded = false;
    private String lastIndexUrl = null;

    private final TextWatcher searchWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(final CharSequence s, final int start, final int count,
                                      final int after) {
        }

        @Override
        public void onTextChanged(final CharSequence s, final int start, final int before,
                                  final int count) {
            applySearchFilter();
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
                loadRepo();
            });
        }

        adapter = new SubtitleRecyclerAdapter();
        adapter.setOnItemClickListener(new OnClickGesture<SubtitleVideoItem>() {
            @Override
            public void selected(final SubtitleVideoItem item) {
                onItemSelected(item);
            }
        });
        listView.setLayoutManager(new LinearLayoutManager(getContext()));
        listView.setAdapter(adapter);

        searchView.addTextChangedListener(searchWatcher);
        // Infinite scroll: load the next batch as the user reaches the end.
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(final androidx.recyclerview.widget.RecyclerView recyclerView,
                                   final int dx, final int dy) {
                if (dy <= 0) {
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

        loadRepo();
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
                loadRepo();
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (searchView != null) {
            searchView.removeTextChangedListener(searchWatcher);
        }
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
        adapter = null;
    }

    private void onItemSelected(final SubtitleVideoItem item) {
        // Set the pending auto-load id BEFORE opening the video, so the player can pick it up once
        // the stream is prepared.
        Player.setPendingAutoLoadSubtitleId(item.videoId);
        NavigationHelper.openVideoDetailFragment(
                requireContext(),
                requireActivity().getSupportFragmentManager(),
                ServiceList.YouTube.getServiceId(),
                SubtitleRepoFetcher.watchUrlFor(item.videoId),
                item.title == null ? item.videoId : item.title,
                null,
                false);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Loading
    ///////////////////////////////////////////////////////////////////////////

    private void loadRepo() {
        showLoading();
        executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            List<SubtitleVideoItem> result = new ArrayList<>();
            boolean failed = false;
            try {
                result = SubtitleRepoFetcher.fetchRepoSubtitles(getContext());
            } catch (final Exception e) {
                failed = true;
            }
            final List<SubtitleVideoItem> finalResult = result;
            final boolean finalFailed = failed;
            mainHandler.post(() -> {
                if (!isAdded()) {
                    return;
                }
                if (finalFailed) {
                    loaded = true;
                    allItems = new ArrayList<>();
                    filteredItems = new ArrayList<>();
                    showMessage(getString(R.string.subtitle_jump_empty));
                    return;
                }
                loaded = true;
                lastIndexUrl = org.schabi.newpipe.subtitles.SubtitleRepoFetcher.currentIndexUrl(getContext());
                allItems = finalResult;
                displayCount = BATCH_SIZE;
                applySearchFilter();
                if (filteredItems.isEmpty()) {
                    showMessage(getString(R.string.subtitle_jump_empty));
                } else {
                    showList();
                    showBatch();
                }
            });
        });
    }

    ///////////////////////////////////////////////////////////////////////////
    // Search + batching
    ///////////////////////////////////////////////////////////////////////////

    private void applySearchFilter() {
        if (!loaded || searchView == null) {
            return;
        }
        final String query = searchView.getText() == null ? "" : searchView.getText().toString();
        filteredItems = new ArrayList<>();
        for (final SubtitleVideoItem item : allItems) {
            if (item.matches(query)) {
                filteredItems.add(item);
            }
        }
        displayCount = BATCH_SIZE;
        if (filteredItems.isEmpty()) {
            // Clear the adapter and show "no results" — otherwise stale rows from the previous
            // query stay visible.
            if (adapter != null) {
                adapter.setItems(new java.util.ArrayList<>());
            }
            showMessage(getString(R.string.subtitle_jump_empty));
        } else {
            showList();
            showBatch();
        }
    }

    private void showBatch() {
        if (adapter == null || filteredItems.isEmpty()) {
            if (loadMoreButton != null) {
                loadMoreButton.setVisibility(View.GONE);
            }
            return;
        }
        final int end = Math.min(displayCount, filteredItems.size());
        final List<SubtitleVideoItem> batch = new ArrayList<>(filteredItems.subList(0, end));
        adapter.setItems(batch);
        // Loading is driven by infinite scroll, not the button.
        if (loadMoreButton != null) {
            loadMoreButton.setVisibility(View.GONE);
        }
    }

    /** Load the next batch when the user scrolls near the end of the list. */
    private void tryLoadMore() {
        if (filteredItems == null || displayCount >= filteredItems.size()) {
            return;
        }
        displayCount = Math.min(displayCount + BATCH_SIZE, filteredItems.size());
        showBatch();
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
