package pro.sketchware.activities.code;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import pro.sketchware.R;
import pro.sketchware.databinding.ItemProjectFileExplorerBinding;

/**
 * List adapter for the Code Mode file explorer. Renders section headers,
 * directories, regular files and generated-file entries (which carry an
 * extra "customized" flag and a customize-target path).
 */
public final class ProjectFileTreeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int TYPE_HEADER = 0;
    public static final int TYPE_DIRECTORY = 1;
    public static final int TYPE_FILE = 2;
    /** A generated source file entry (activity java / layout xml / manifest). */
    public static final int TYPE_GENERATED = 3;

    public static final class Item {
        public final int type;
        /** Absolute path of the item on disk. */
        @NonNull
        public final String path;
        @NonNull
        public final String title;
        /** Optional subtitle (directory description). */
        @Nullable
        public final String description;
        /** Only for {@link #TYPE_GENERATED}: absolute path of the customized copy. */
        @Nullable
        public final String customizedPath;
        /** Only for {@link #TYPE_GENERATED}: "activity/class", "layout" or "manifest". */
        @Nullable
        public final String generatedKind;
        public final boolean isCustomized;
        /** Convenience flag: TYPE_GENERATED or a flagged file. */
        public final boolean isGenerated;

        private Item(int type, @NonNull String path, @NonNull String title,
                     @Nullable String description, @Nullable String customizedPath,
                     @Nullable String generatedKind, boolean isCustomized, boolean isGenerated) {
            this.type = type;
            this.path = path;
            this.title = title;
            this.description = description;
            this.customizedPath = customizedPath;
            this.generatedKind = generatedKind;
            this.isCustomized = isCustomized;
            this.isGenerated = isGenerated;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Item)) return false;
            Item item = (Item) o;
            return type == item.type
                    && isCustomized == item.isCustomized
                    && isGenerated == item.isGenerated
                    && path.equals(item.path)
                    && title.equals(item.title)
                    && java.util.Objects.equals(description, item.description)
                    && java.util.Objects.equals(customizedPath, item.customizedPath)
                    && java.util.Objects.equals(generatedKind, item.generatedKind);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(type, path, title, description, customizedPath, generatedKind, isCustomized, isGenerated);
        }
    }

    public static Item header(@NonNull String title) {
        return new Item(TYPE_HEADER, "", title, null, null, null, false, false);
    }

    public static Item directory(@NonNull String path, @NonNull String title, @Nullable String description) {
        return new Item(TYPE_DIRECTORY, path, title, description, null, null, false, false);
    }

    /** Regular user-owned file; {@code customized} only drives the "customized" badge. */
    public static Item file(@NonNull String path, @NonNull String title, boolean customized) {
        return new Item(TYPE_FILE, path, title, null, null, null, customized, false);
    }

    public static Item generated(@NonNull String path, @NonNull String title,
                                 @NonNull String kind, @Nullable String customizedPath,
                                 boolean isCustomized) {
        return new Item(TYPE_GENERATED, path, title, null, customizedPath, kind, isCustomized, true);
    }

    interface Listener {
        void onItemClicked(@NonNull Item item);

        void onItemLongClicked(@NonNull View view, @NonNull Item item);
    }

    private static final int VIEW_TYPE_HEADER = 1;
    private static final int VIEW_TYPE_ROW = 2;

    private final List<Item> items = new ArrayList<>();
    private final Listener listener;

    public ProjectFileTreeAdapter(@NonNull Listener listener) {
        this.listener = listener;
        setHasStableIds(false);
    }

    public void submitList(@NonNull List<Item> newList) {
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return items.size();
            }

            @Override
            public int getNewListSize() {
                return newList.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                Item a = items.get(oldItemPosition);
                Item b = newList.get(newItemPosition);
                if (a.type != b.type) {
                    return false;
                }
                if (a.type == TYPE_HEADER) {
                    return a.title.equals(b.title);
                }
                return a.path.equals(b.path);
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return items.get(oldItemPosition).equals(newList.get(newItemPosition));
            }
        });
        items.clear();
        items.addAll(newList);
        result.dispatchUpdatesTo(this);
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).type == TYPE_HEADER ? VIEW_TYPE_HEADER : VIEW_TYPE_ROW;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_HEADER) {
            TextViewHolder holder = new TextViewHolder(inflater,
                    android.R.layout.simple_list_item_1, parent, true);
            return holder;
        }
        return new RowViewHolder(ItemProjectFileExplorerBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Item item = items.get(position);
        if (holder instanceof TextViewHolder) {
            ((TextViewHolder) holder).text.setText(item.title);
            return;
        }
        RowViewHolder row = (RowViewHolder) holder;
        row.binding.title.setText(item.title);
        if (item.description != null) {
            row.binding.subtitle.setVisibility(View.VISIBLE);
            row.binding.subtitle.setText(item.description);
        } else {
            row.binding.subtitle.setVisibility(View.GONE);
        }
        row.binding.icon.setImageResource(iconFor(item));
        if (item.isGenerated && !item.isCustomized) {
            row.binding.badge.setVisibility(View.VISIBLE);
            row.binding.badge.setText("generated");
        } else if (item.isCustomized) {
            row.binding.badge.setVisibility(View.VISIBLE);
            row.binding.badge.setText("customized");
        } else {
            row.binding.badge.setVisibility(View.GONE);
        }
        row.binding.getRoot().setOnClickListener(v -> listener.onItemClicked(item));
        row.binding.getRoot().setOnLongClickListener(v -> {
            listener.onItemLongClicked(v, item);
            return true;
        });
    }

    private static int iconFor(@NonNull Item item) {
        switch (item.type) {
            case TYPE_DIRECTORY:
                return R.drawable.ic_mtrl_folder;
            case TYPE_GENERATED:
                if (item.title.endsWith(".xml")) {
                    return "layout".equals(item.generatedKind)
                            ? R.drawable.ic_mtrl_screen : R.drawable.ic_mtrl_code;
                }
                return R.drawable.ic_mtrl_java;
            default:
                String lower = item.title.toLowerCase();
                if (lower.endsWith(".java")) {
                    return R.drawable.ic_mtrl_java;
                } else if (lower.endsWith(".kt") || lower.endsWith(".kts")) {
                    return R.drawable.ic_mtrl_kotlin;
                } else if (lower.endsWith(".xml")) {
                    return R.drawable.ic_mtrl_code;
                }
                return R.drawable.ic_mtrl_file;
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class TextViewHolder extends RecyclerView.ViewHolder {
        final android.widget.TextView text;

        TextViewHolder(@NonNull LayoutInflater inflater, int resource,
                       @NonNull ViewGroup parent, boolean header) {
            super(inflater.inflate(resource, parent, false));
            text = itemView.findViewById(android.R.id.text1);
            if (text != null) {
                text.setAllCaps(true);
                text.setTextSize(12f);
                int pad = (int) (itemView.getResources().getDisplayMetrics().density * 12);
                text.setPadding(pad, header ? pad : pad / 2, pad, pad / 2);
                text.setTextColor(itemView.getResources()
                        .getColor(pro.sketchware.R.color.design_default_color_secondary, null));
            }
        }
    }

    public static final class RowViewHolder extends RecyclerView.ViewHolder {
        final ItemProjectFileExplorerBinding binding;

        RowViewHolder(@NonNull ItemProjectFileExplorerBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
