package pro.sketchware.activities.code;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import pro.sketchware.R;
import pro.sketchware.databinding.ItemProjectFileExplorerBinding;

/**
 * Tree adapter for the Code Mode file explorer. Renders a real, collapsible
 * hierarchy: directories with chevrons and depth indentation, files nested
 * inside them, plus virtual entries for block-generated files that do not
 * exist on disk yet.
 */
public final class ProjectFileTreeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int TYPE_DIRECTORY = 1;
    public static final int TYPE_FILE = 2;
    /** Virtual node: block-generated file that has no on-disk copy yet. */
    public static final int TYPE_GENERATED = 3;

    public static final String KIND_ACTIVITY = "activity";
    public static final String KIND_LAYOUT = "layout";

    public static final class Node {
        public final int type;
        @NonNull
        public final String title;
        /** Absolute path; for virtual generated nodes it is the planned override target. */
        @NonNull
        public final String path;
        /** Absolute path of the on-disk directory this node lives in ("" for virtual roots). */
        @NonNull
        public final String parentPath;
        @Nullable
        public final String generatedKind;
        public final boolean isCustomized;
        public final int depth;
        public final boolean expanded;
        /** Child count for directories (recursive); -1 when unknown/not computed. */
        public final int recursiveCount;

        Node(int type, @NonNull String title, @NonNull String path, @NonNull String parentPath,
             @Nullable String generatedKind, boolean isCustomized, int depth,
             boolean expanded, int recursiveCount) {
            this.type = type;
            this.title = title;
            this.path = path;
            this.parentPath = parentPath;
            this.generatedKind = generatedKind;
            this.isCustomized = isCustomized;
            this.depth = depth;
            this.expanded = expanded;
            this.recursiveCount = recursiveCount;
        }

        Node withExpanded(boolean value) {
            return new Node(type, title, path, parentPath, generatedKind, isCustomized, depth, value, recursiveCount);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Node)) return false;
            Node n = (Node) o;
            return type == n.type
                    && isCustomized == n.isCustomized
                    && depth == n.depth
                    && expanded == n.expanded
                    && recursiveCount == n.recursiveCount
                    && path.equals(n.path)
                    && title.equals(n.title)
                    && parentPath.equals(n.parentPath)
                    && java.util.Objects.equals(generatedKind, n.generatedKind);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(type, path, title, parentPath, generatedKind, isCustomized, depth, expanded, recursiveCount);
        }
    }

    interface Listener {
        void onNodeClicked(@NonNull Node node);

        void onNodeMenuRequested(@NonNull View anchor, @NonNull Node node);

        /** Rebuild the visible list after an expansion state change. */
        void onExpansionChanged();
    }

    private static final int VIEW_TYPE_NODE = 1;

    private final List<Node> visible = new ArrayList<>();
    private final List<Node> allNodes = new ArrayList<>();
    private final Listener listener;
    private final Map<String, Boolean> expandedState = new HashMap<>();

    public ProjectFileTreeAdapter(@NonNull Listener listener) {
        this.listener = listener;
        setHasStableIds(false);
    }

    /**
     * Sets the full model (flattened hierarchical node list including hidden children)
     * and re-applies the expansion state.
     */
    public void submitModel(@NonNull List<Node> model) {
        allNodes.clear();
        allNodes.addAll(model);
        rebuildVisible();
    }

    /** Remembers whether a directory is expanded (persists across model updates). */
    public void setExpanded(@NonNull String path, boolean value) {
        expandedState.put(path, value);
    }

    public boolean isExpanded(@NonNull String path) {
        return expandedState.getOrDefault(path, false);
    }

    /** @return all directory paths currently marked expanded (for state persistence). */
    @NonNull
    public List<String> getExpandedPaths() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : expandedState.entrySet()) {
            if (e.getValue()) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** @return the full model (including nodes hidden by collapsed ancestors). */
    @NonNull
    public List<Node> currentModel() {
        return new ArrayList<>(allNodes);
    }

    private void rebuildVisible() {
        List<Node> result = new ArrayList<>();
        for (int i = 0; i < allNodes.size(); i++) {
            Node node = allNodes.get(i);
            // The expansion map is the source of truth; model entries may be stale.
            boolean expanded = node.type == TYPE_DIRECTORY && isExpanded(node.path);
            if (node.type == TYPE_DIRECTORY && !expanded) {
                // Collapse: skip its children (they directly follow until depth <= node.depth).
                int j = i + 1;
                while (j < allNodes.size() && allNodes.get(j).depth > node.depth) {
                    j++;
                }
                i = j - 1;
                result.add(node);
                continue;
            }
            result.add(node);
        }
        visible.clear();
        visible.addAll(result);
        notifyDataSetChanged();
    }

    @NonNull
    public List<Node> getVisibleList() {
        return new ArrayList<>(visible);
    }

    @Override
    public int getItemViewType(int position) {
        return VIEW_TYPE_NODE;
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
        vh.binding.title.setText(node.title);

        // Chevron for directories, else a spacer to align files with folder children.
        if (node.type == TYPE_DIRECTORY) {
            vh.binding.chevron.setVisibility(View.VISIBLE);
            vh.binding.chevron.setRotation(isExpanded(node.path) ? 90f : 0f);
        } else {
            vh.binding.chevron.setVisibility(View.INVISIBLE);
            vh.binding.chevron.setRotation(0f);
        }

        int indentPx = (int) (16 * vh.binding.getRoot().getResources().getDisplayMetrics().density);
        vh.binding.indent.setPadding(node.depth * indentPx, 0, 0, 0);

        vh.binding.icon.setImageResource(iconFor(node));
        if (node.type == TYPE_GENERATED) {
            vh.binding.badge.setVisibility(View.VISIBLE);
            vh.binding.badge.setText(R.string.file_explorer_badge_generated);
        } else if (node.isCustomized) {
            vh.binding.badge.setVisibility(View.VISIBLE);
            vh.binding.badge.setText(R.string.file_explorer_badge_customized);
        } else {
            vh.binding.badge.setVisibility(View.GONE);
        }

        vh.binding.getRoot().setOnClickListener(v -> listener.onNodeClicked(node));
        View.OnClickListener menu = v -> listener.onNodeMenuRequested(v, node);
        vh.binding.getRoot().setOnLongClickListener(v -> {
            menu.onClick(v);
            return true;
        });
        vh.binding.more.setOnClickListener(menu);
    }

    private static int iconFor(@NonNull Node node) {
        if (node.type == TYPE_DIRECTORY) {
            return R.drawable.ic_mtrl_folder;
        }
        String lower = node.title.toLowerCase();
        if (lower.endsWith(".java")) {
            return R.drawable.ic_mtrl_java;
        } else if (lower.endsWith(".kt") || lower.endsWith(".kts")) {
            return R.drawable.ic_mtrl_kotlin;
        } else if (lower.endsWith(".xml")) {
            return R.drawable.ic_mtrl_code;
        } else if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".webp")
                || lower.endsWith(".gif") || lower.endsWith(".9.png")) {
            return R.drawable.ic_mtrl_image;
        } else if (lower.endsWith(".json") || lower.endsWith(".txt") || lower.endsWith(".pro")) {
            return R.drawable.ic_mtrl_file;
        }
        return R.drawable.ic_mtrl_file;
    }

    @Override
    public int getItemCount() {
        return visible.size();
    }

    public static final class NodeViewHolder extends RecyclerView.ViewHolder {
        final ItemProjectFileExplorerBinding binding;

        NodeViewHolder(@NonNull ItemProjectFileExplorerBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
