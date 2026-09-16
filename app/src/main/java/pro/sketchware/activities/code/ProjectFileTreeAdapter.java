package pro.sketchware.activities.code;

import android.os.Handler;
import android.os.Looper;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import pro.sketchware.R;
import pro.sketchware.databinding.ItemProjectFileExplorerBinding;

/**
 * Real, collapsible, lazily-loaded project file tree for Code Mode.
 * <p>
 * Model: a {@link Node} tree whose children are discovered on demand the first
 * time a folder is expanded (mirroring how a desktop/desktop-style file tree
 * behaves). Nothing is hardcoded: every directory child comes from the project's
 * own directory listing, and the activity merges virtual entries for
 * block-generated files in through {@link ChildrenProvider}.
 * <p>
 * Expansion state lives here (keyed by absolute path) so it survives refreshes,
 * rotation and re-entry, and is exposed via {@link #getExpandedPaths()} for
 * persistence.
 */
public final class ProjectFileTreeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    /** Node kinds. Directories are expandable, the rest are leaves. */
    public static final int TYPE_DIRECTORY = 0;
    public static final int TYPE_FILE = 1;
    /**
     * File that block mode generates and that has no on-disk copy yet. It can be
     * opened read-only and "customized" into a real {@link #TYPE_FILE}.
     */
    public static final int TYPE_GENERATED = 2;

    public static final String KIND_ACTIVITY = "activity";
    public static final String KIND_LAYOUT = "layout";
    public static final String KIND_MANIFEST = "manifest";

    /** Supplies the children of a directory. Implementations may do file I/O. */
    public interface ChildrenProvider {
        /**
         * @param directory absolute path of the directory to list
         * @return that directory's real (and virtual generated) children, directories
         * first and each group sorted by name. Never {@code null}.
         */
        @NonNull
        List<Node> childrenOf(@NonNull String directory);
    }

    /** Notified after every re-render, so the host can update screen-level state. */
    public interface RenderListener {
        void onRendered();
    }

    /** Callbacks for row interactions. */
    public interface Listener {
        void onNodeClicked(@NonNull Node node);

        void onNodeMenuRequested(@NonNull View anchor, @NonNull Node node);
    }

    public static final class Node {
        public final int type;
        @NonNull
        public final String name;
        /**
         * Absolute path. For {@link #TYPE_GENERATED} nodes this is the path the
         * file would occupy once customized into the project.
         */
        @NonNull
        public final String path;
        @Nullable
        public final String generatedKind;
        /** For {@link #TYPE_FILE}: this file overrides a block-generated file. */
        public final boolean customized;
        /** Number of direct children, or -1 when not applicable. */
        public final int childCount;
        /** Current depth, assigned while flattening. */
        public int depth;
        /** Expansion snapshot, kept in sync while flattening (used for diffing). */
        public boolean expanded;

        public Node(int type, @NonNull String name, @NonNull String path,
                    @Nullable String generatedKind, boolean customized, int childCount) {
            this.type = type;
            this.name = name;
            this.path = path;
            this.generatedKind = generatedKind;
            this.customized = customized;
            this.childCount = childCount;
        }

        public boolean isDirectory() {
            return type == TYPE_DIRECTORY;
        }

        @NonNull
        @Override
        public String toString() {
            return "Node{" + type + ", " + name + ", " + path + "}";
        }
    }

    private final Listener listener;
    private final ChildrenProvider childrenProvider;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Children per directory path; a present key means "already loaded". */
    private final Map<String, List<Node>> childrenCache = new HashMap<>();
    /** Paths of directories currently expanded. */
    private final Set<String> expandedPaths = new LinkedHashSet<>();
    /** Directories whose listing is in flight (rendered with a spinner). */
    private final Set<String> loadingPaths = new LinkedHashSet<>();
    /** The flattened, visible rows. */
    private final List<Node> visible = new ArrayList<>();
    /** Set once the host activity is destroyed; stops further background work. */
    private boolean shutDown;

    @Nullable
    private Node root;
    @Nullable
    private RenderListener renderListener;

    public ProjectFileTreeAdapter(@NonNull Listener listener, @NonNull ChildrenProvider childrenProvider) {
        this.listener = listener;
        this.childrenProvider = childrenProvider;
    }

    public void setRenderListener(@Nullable RenderListener renderListener) {
        this.renderListener = renderListener;
    }

    /** Whether {@code directory}'s children have been listed (possibly empty). */
    public boolean isLoaded(@NonNull String directory) {
        return childrenCache.containsKey(directory);
    }

    /** Whether {@code directory} is known to contain at least one entry. */
    public boolean hasChildren(@NonNull String directory) {
        List<Node> children = childrenCache.get(directory);
        return children != null && !children.isEmpty();
    }

    //region Model

    /**
     * Sets (or replaces) the tree root and re-renders. The root is expanded by
     * default so the project's top-level folders are visible immediately.
     */
    public void setRoot(@NonNull Node rootNode) {
        this.root = rootNode;
        expandedPaths.add(rootNode.path);
        render();
    }

    /**
     * Drops every cached listing and reloads what is expanded, so external changes
     * (other managers, builds, file imports) show up on return to the screen.
     */
    public void invalidateAndReload() {
        childrenCache.clear();
        loadingPaths.clear();
        if (root != null) {
            render();
        }
    }

    /** Drops the cached listing of one directory and re-renders it. */
    public void invalidate(@NonNull String directory) {
        childrenCache.remove(directory);
        if (root != null) {
            render();
        }
    }

    /**
     * Forgets a directory and everything beneath it - used when a folder is
     * deleted or renamed, so no stale expansion or cache entry outlives it.
     */
    public void forgetSubtree(@NonNull String directory) {
        String prefix = directory + File.separator;
        expandedPaths.removeIf(path -> path.equals(directory) || path.startsWith(prefix));
        loadingPaths.removeIf(path -> path.equals(directory) || path.startsWith(prefix));
        childrenCache.keySet().removeIf(path -> path.equals(directory) || path.startsWith(prefix));
    }

    /** Whether {@code path} is currently expanded. */
    public boolean isExpanded(@NonNull String path) {
        return expandedPaths.contains(path);
    }

    /** Restores expansion state (e.g. from a saved session) before the first render. */
    public void setExpanded(@NonNull String path, boolean expanded) {
        if (expanded) {
            expandedPaths.add(path);
        } else {
            expandedPaths.remove(path);
        }
    }

    @NonNull
    public List<String> getExpandedPaths() {
        return new ArrayList<>(expandedPaths);
    }

    /**
     * Stops background listings. Called from the host activity's
     * {@code onDestroy}; after this the adapter renders from cache only.
     */
    public void shutdown() {
        shutDown = true;
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }

    //endregion

    //region Expansion

    /** Toggles a directory. Expanding triggers an asynchronous listing. */
    public void toggle(@NonNull Node node) {
        if (!node.isDirectory()) {
            return;
        }
        if (!expandedPaths.remove(node.path)) {
            expandedPaths.add(node.path);
        }
        render();
    }

    //endregion

    //region Rendering

    /**
     * Flattens the expanded tree into the visible row list and schedules a listing
     * for every expanded directory that has not been loaded yet. Nothing recurses:
     * completions post back to the main thread and simply render again.
     */
    private void render() {
        // 1. Every expanded directory must be cached or loading, so rows can show
        //    either their children or a spinner - never a silently empty folder.
        for (String path : new ArrayList<>(expandedPaths)) {
            if (!childrenCache.containsKey(path) && loadingPaths.add(path)) {
                scheduleLoad(path);
            }
        }

        // 2. Flatten root -> expanded children, assigning depth as we go.
        List<Node> flattened = new ArrayList<>();
        if (root != null) {
            flatten(root, 0, flattened);
        }

        // 3. Animate the transition between the old and new row lists.
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new NodeDiff(visible, flattened), true);
        visible.clear();
        visible.addAll(flattened);
        diff.dispatchUpdatesTo(this);

        // 4. Let the host react even when nothing changed (an empty listing produces
        //    no diff, but the empty state still has to appear).
        if (renderListener != null) {
            renderListener.onRendered();
        }
    }

    private void scheduleLoad(@NonNull String directory) {
        if (shutDown) {
            return;
        }
        executor.execute(() -> {
            List<Node> children;
            try {
                children = childrenProvider.childrenOf(directory);
            } catch (Exception e) {
                children = new ArrayList<>();
            }
            List<Node> result = children;
            if (shutDown) {
                return;
            }
            mainHandler.post(() -> {
                if (shutDown) {
                    return;
                }
                loadingPaths.remove(directory);
                childrenCache.put(directory, result);
                render();
            });
        });
    }

    private void flatten(@NonNull Node node, int depth, @NonNull List<Node> out) {
        node.depth = depth;
        node.expanded = node.isDirectory() && expandedPaths.contains(node.path);
        out.add(node);
        if (!node.expanded) {
            return;
        }
        List<Node> children = childrenCache.get(node.path);
        if (children == null) {
            return;
        }
        for (Node child : children) {
            flatten(child, depth + 1, out);
        }
    }

    @Override
    public int getItemCount() {
        return visible.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new NodeViewHolder(ItemProjectFileExplorerBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Node node = visible.get(position);
        NodeViewHolder vh = (NodeViewHolder) holder;
        boolean expanded = node.expanded;
        boolean loading = loadingPaths.contains(node.path);

        vh.binding.title.setText(node.name);

        // Depth indentation: fixed step per level, no hardcoded device offsets.
        int step = vh.binding.getRoot().getResources().getDimensionPixelSize(R.dimen.file_explorer_indent);
        int indent = Math.min(node.depth, 12) * step;
        ViewGroup.LayoutParams indentParams = vh.binding.indent.getLayoutParams();
        if (indentParams.width != indent) {
            indentParams.width = indent;
            vh.binding.indent.setLayoutParams(indentParams);
        }

        if (node.isDirectory()) {
            vh.binding.chevron.setVisibility(View.VISIBLE);
            vh.binding.chevron.setImageResource(R.drawable.ic_mtrl_chevron_right_24);
            vh.binding.chevron.setAlpha(1f);
            float target = expanded ? 90f : 0f;
            if (vh.boundPath != null && vh.boundPath.equals(node.path)) {
                vh.binding.chevron.animate().rotation(target).setDuration(140).start();
            } else {
                vh.binding.chevron.animate().cancel();
                vh.binding.chevron.setRotation(target);
            }
            vh.binding.progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        } else {
            vh.binding.chevron.setVisibility(View.INVISIBLE);
            vh.binding.progress.setVisibility(View.GONE);
        }
        vh.boundPath = node.path;

        vh.binding.icon.setImageResource(iconFor(node));
        vh.binding.icon.setAlpha(node.type == TYPE_GENERATED ? 0.7f : 1f);

        bindBadge(vh, node, expanded);
        vh.binding.getRoot().setOnClickListener(v -> listener.onNodeClicked(node));
        View.OnClickListener menu = v -> listener.onNodeMenuRequested(v, node);
        vh.binding.getRoot().setOnLongClickListener(v -> {
            menu.onClick(v);
            return true;
        });
        vh.binding.more.setOnClickListener(menu);
    }

    private void bindBadge(@NonNull NodeViewHolder vh, @NonNull Node node, boolean expanded) {
        if (node.type == TYPE_GENERATED) {
            vh.binding.badge.setVisibility(View.VISIBLE);
            vh.binding.badge.setText(R.string.file_explorer_badge_generated);
        } else if (node.customized) {
            vh.binding.badge.setVisibility(View.VISIBLE);
            vh.binding.badge.setText(R.string.file_explorer_badge_customized);
        } else if (node.isDirectory() && !expanded && node.childCount > 0) {
            vh.binding.badge.setVisibility(View.VISIBLE);
            vh.binding.badge.setText(String.format(Locale.getDefault(), "%d", node.childCount));
        } else {
            vh.binding.badge.setVisibility(View.GONE);
        }
    }

    private static int iconFor(@NonNull Node node) {
        if (node.isDirectory()) {
            return node.depth == 0 ? R.drawable.ic_mtrl_code : R.drawable.ic_mtrl_folder;
        }
        String lower = node.name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) {
            return R.drawable.ic_mtrl_java;
        } else if (lower.endsWith(".kt") || lower.endsWith(".kts")) {
            return R.drawable.ic_mtrl_kotlin;
        } else if (lower.endsWith(".xml") || lower.endsWith(".gradle")) {
            return R.drawable.ic_mtrl_code;
        } else if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".svg")) {
            return R.drawable.ic_mtrl_image;
        }
        return R.drawable.ic_mtrl_file;
    }

    private final class NodeDiff extends DiffUtil.Callback {
        private final List<Node> oldList;
        private final List<Node> newList;

        NodeDiff(@NonNull List<Node> oldList, @NonNull List<Node> newList) {
            this.oldList = new ArrayList<>(oldList);
            this.newList = newList;
        }

        @Override
        public int getOldListSize() {
            return oldList.size();
        }

        @Override
        public int getNewListSize() {
            return newList.size();
        }

        @Override
        public boolean areItemsTheSame(int oldPos, int newPos) {
            return oldList.get(oldPos).path.equals(newList.get(newPos).path);
        }

        @Override
        public boolean areContentsTheSame(int oldPos, int newPos) {
            Node a = oldList.get(oldPos);
            Node b = newList.get(newPos);
            return a.type == b.type
                    && a.depth == b.depth
                    && a.expanded == b.expanded
                    && a.customized == b.customized
                    && a.childCount == b.childCount
                    && a.name.equals(b.name)
                    && Objects.equals(a.generatedKind, b.generatedKind);
        }
    }

    public static final class NodeViewHolder extends RecyclerView.ViewHolder {
        final ItemProjectFileExplorerBinding binding;
        @Nullable
        String boundPath;

        NodeViewHolder(@NonNull ItemProjectFileExplorerBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    /** Convenience: a directory node with an immediate child count. */
    @NonNull
    public static Node directory(@NonNull String name, @NonNull String path, int childCount) {
        return new Node(TYPE_DIRECTORY, name, path, null, false, childCount);
    }

    /** Convenience: a real on-disk file node. */
    @NonNull
    public static Node file(@NonNull String name, @NonNull String path, boolean customized) {
        return new Node(TYPE_FILE, name, path, null, customized, -1);
    }

    /** Convenience: a block-generated file that has no on-disk copy yet. */
    @NonNull
    public static Node generated(@NonNull String name, @NonNull String path, @Nullable String kind) {
        return new Node(TYPE_GENERATED, name, path, kind, false, -1);
    }

    /** Convenience: the project root row. */
    @NonNull
    public static Node project(@NonNull String name, @NonNull String path) {
        return new Node(TYPE_DIRECTORY, name, path, null, false, -1);
    }
}
