package pro.sketchware.activities.code;

import static pro.sketchware.utility.GsonUtils.getGson;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.besome.sketch.beans.ProjectFileBean;
import com.besome.sketch.lib.base.BaseAppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import a.a.a.hC;
import a.a.a.jC;
import mod.hey.studios.util.Helper;
import pro.sketchware.R;
import pro.sketchware.databinding.ActivityProjectFileExplorerBinding;
import pro.sketchware.utility.FilePathUtil;
import pro.sketchware.utility.FileResConfig;
import pro.sketchware.utility.FileUtil;
import pro.sketchware.utility.SketchwareUtil;

/**
 * Code Mode entry point: a real, collapsible file tree of the Sketchware Pro
 * project being edited, rooted at ".sketchware/data/&lt;sc_id&gt;/files".
 * <p>
 * The tree shows every folder exactly as it exists on disk, merged with virtual
 * entries for block-generated activities/layouts that have no on-disk copy yet.
 * Files opened here are the exact files the build pipeline consumes:
 * <ul>
 *     <li><b>java</b> - custom Java/Kotlin sources, compiled by kotlinc + ECJ,</li>
 *     <li><b>resource</b> - res overrides compiled by aapt2 (values, drawable, ...),</li>
 *     <li><b>assets</b> - packaged into the APK as-is.</li>
 * </ul>
 * All file operations (create, rename, delete, duplicate, copy/cut/paste,
 * import) are provided through Material dialogs. Generated files can be
 * previewed read-only and customized into the tree via "Customize".
 */
public class ProjectFileExplorerActivity extends BaseAppCompatActivity
        implements ProjectFileTreeAdapter.Listener {

    public static final String EXTRA_SC_ID = "sc_id";

    private static final String STATE_EXPANDED = "expanded_paths";

    /** Pending clipboard file for copy/cut ("cut" removes the source on paste). */
    private enum ClipboardOp {COPY, CUT}

    private ActivityProjectFileExplorerBinding binding;
    private ProjectFileTreeAdapter adapter;
    private ProjectFileCatalog catalog;
    private FileResConfig frc;
    private String scId;
    private String scDataFilesPath;
    private String clipboardPath;
    private ClipboardOp clipboardOp;
    /** Directory the SAF import places files into; null when import was not started from a row. */
    @Nullable
    private String importTargetDir;

    private final OnBackPressedCallback backPressedCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            // Nothing to unwind any more (no directory navigation); let the system proceed.
            setEnabled(false);
            getOnBackPressedDispatcher().onBackPressed();
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        enableEdgeToEdgeNoContrast();
        super.onCreate(savedInstanceState);
        binding = ActivityProjectFileExplorerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        scId = getIntent().getStringExtra(EXTRA_SC_ID);
        if (scId == null || scId.isEmpty()) {
            SketchwareUtil.toastError("Missing project id");
            finish();
            return;
        }

        FilePathUtil fpu = new FilePathUtil();
        scDataFilesPath = new File(fpu.getPathJava(scId)).getParentFile().getAbsolutePath();
        catalog = ProjectFileCatalog.load(scId);
        frc = new FileResConfig(scId);

        setSupportActionBar(binding.toolbar);
        binding.toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        getOnBackPressedDispatcher().addCallback(this, backPressedCallback);

        adapter = new ProjectFileTreeAdapter(this);
        binding.fileList.setLayoutManager(new LinearLayoutManager(this));
        binding.fileList.setAdapter(adapter);

        ensureDirectoryExists(new File(scDataFilesPath, "java"));
        ensureDirectoryExists(new File(scDataFilesPath, "resource"));

        if (savedInstanceState != null) {
            List<String> expanded = savedInstanceState.getStringArrayList(STATE_EXPANDED);
            if (expanded != null) {
                for (String path : expanded) {
                    adapter.setExpanded(path, true);
                }
            }
        }

        binding.openEditorButton.setOnClickListener(v ->
                startActivity(new Intent(getApplicationContext(), ProjectCodeEditorActivity.class)
                        .putExtra(ProjectCodeEditorActivity.EXTRA_SC_ID, scId)));

        refresh();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putStringArrayList(STATE_EXPANDED, new ArrayList<>(adapter.getExpandedPaths()));
    }

    @Override
    public void onResume() {
        super.onResume();
        // finish() may already be scheduled (missing sc_id): catalog is null then.
        if (catalog == null || isFinishing()) {
            return;
        }
        // External changes (other managers, builds) are picked up on return.
        refresh();
    }

    private void ensureDirectoryExists(@NonNull File directory) {
        if (!directory.exists() && !directory.mkdirs()) {
            SketchwareUtil.toastError("Could not create " + directory.getName());
        }
    }

    //region Tree building

    private void refresh() {
        catalog.pruneMissing();
        List<ProjectFileTreeAdapter.Node> model = new ArrayList<>();

        // Java sources: real folders (with packages) + custom files + generated activities.
        File javaDir = new File(scDataFilesPath, "java");
        appendDirectoryTree(model, javaDir, 0, "");
        appendGeneratedJava(model, javaDir, 0);

        // Resources: real folders + generated layouts merged into resource/layout.
        File resourceDir = new File(scDataFilesPath, "resource");
        appendDirectoryTree(model, resourceDir, 0, "");
        appendGeneratedLayouts(model, new File(resourceDir, "layout"), 1);

        // Assets, broadcast receivers, services.
        appendDirectoryTree(model, new File(scDataFilesPath, "assets"), 0, "assets");
        appendDirectoryTree(model, new File(scDataFilesPath, "broadcast"), 0, "broadcast");
        appendDirectoryTree(model, new File(scDataFilesPath, "service"), 0, "service");

        // Virtual manifest entry (view/edit through the app's manifest editor).
        model.add(new ProjectFileTreeAdapter.Node(ProjectFileTreeAdapter.TYPE_GENERATED,
                "AndroidManifest.xml", "", "", null, false, 0, false, -1));

        adapter.submitModel(model);
        boolean empty = model.isEmpty();
        binding.noFilesLayout.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.fileList.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    /**
     * Appends {@code dir} and (collapsed by default) its whole subtree. When
     * {@code forcedName} is non-empty the top directory gets that display name
     * (used to label root categories such as "assets").
     */
    private void appendDirectoryTree(@NonNull List<ProjectFileTreeAdapter.Node> out,
                                     @NonNull File dir, int depth, @Nullable String forcedName) {
        if (!dir.isDirectory()) {
            if (forcedName != null && !forcedName.isEmpty()) {
                // Category root that does not exist yet: still offer creation.
                out.add(new ProjectFileTreeAdapter.Node(ProjectFileTreeAdapter.TYPE_DIRECTORY,
                        forcedName, dir.getAbsolutePath(), "", null, false, depth, false, -1));
            }
            return;
        }
        String display = forcedName != null && !forcedName.isEmpty() ? forcedName : dir.getName();
        out.add(new ProjectFileTreeAdapter.Node(ProjectFileTreeAdapter.TYPE_DIRECTORY,
                display, dir.getAbsolutePath(), "", null, false, depth, false, countRecursive(dir)));
        appendChildren(out, dir, depth + 1);
    }

    private void appendChildren(@NonNull List<ProjectFileTreeAdapter.Node> out,
                                @NonNull File dir, int depth) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        List<File> directories = new ArrayList<>();
        List<File> files = new ArrayList<>();
        for (File child : children) {
            if (child.isDirectory()) {
                directories.add(child);
            } else {
                files.add(child);
            }
        }
        directories.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        files.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File d : directories) {
            out.add(new ProjectFileTreeAdapter.Node(ProjectFileTreeAdapter.TYPE_DIRECTORY,
                    d.getName(), d.getAbsolutePath(), dir.getAbsolutePath(),
                    null, false, depth, false, countRecursive(d)));
            appendChildren(out, d, depth + 1);
        }
        for (File f : files) {
            boolean customized = catalog.findByOverride(toScRelative(f.getAbsolutePath())) != null;
            out.add(new ProjectFileTreeAdapter.Node(ProjectFileTreeAdapter.TYPE_FILE,
                    f.getName(), f.getAbsolutePath(), dir.getAbsolutePath(),
                    null, customized, depth, false, -1));
        }
    }

    /**
     * Appends virtual generated-activity nodes that have no override file on disk yet.
     * Customized copies appear as normal files inside the real java tree instead.
     */
    private void appendGeneratedJava(@NonNull List<ProjectFileTreeAdapter.Node> out,
                                     @NonNull File javaDir, int depth) {
        List<ProjectFileBean> activities = new ArrayList<>();
        try {
            hC projectFileManager = jC.b(scId);
            if (projectFileManager != null && projectFileManager.b() != null) {
                activities.addAll(projectFileManager.b());
            }
        } catch (Exception ignored) {
            // Broken metadata: fall back to disk-only tree.
            return;
        }
        for (ProjectFileBean bean : activities) {
            String javaName = bean.getJavaName();
            if (javaName == null || javaName.isEmpty()) {
                continue;
            }
            File override = new File(javaDir, javaName);
            if (override.isFile()) {
                continue; // already shown as a real file
            }
            out.add(new ProjectFileTreeAdapter.Node(ProjectFileTreeAdapter.TYPE_GENERATED,
                    javaName, override.getAbsolutePath(), javaDir.getAbsolutePath(),
                    ProjectFileTreeAdapter.KIND_ACTIVITY, false, depth, false, -1));
        }
    }

    /**
     * Appends virtual generated-layout nodes under resource/layout (created on demand).
     */
    private void appendGeneratedLayouts(@NonNull List<ProjectFileTreeAdapter.Node> out,
                                        @NonNull File layoutDir, int depth) {
        List<ProjectFileBean> layouts = new ArrayList<>();
        try {
            hC projectFileManager = jC.b(scId);
            if (projectFileManager != null) {
                if (projectFileManager.b() != null) {
                    layouts.addAll(projectFileManager.b());
                }
                if (projectFileManager.c() != null) {
                    layouts.addAll(projectFileManager.c());
                }
            }
        } catch (Exception ignored) {
            return;
        }
        if (layouts.isEmpty()) {
            return;
        }
        out.add(new ProjectFileTreeAdapter.Node(ProjectFileTreeAdapter.TYPE_DIRECTORY,
                layoutDir.getName(), layoutDir.getAbsolutePath(),
                new File(scDataFilesPath, "resource").getAbsolutePath(),
                null, false, depth, false, -1));
        for (ProjectFileBean bean : layouts) {
            String xmlName = bean.getXmlName();
            if (xmlName == null || xmlName.isEmpty()) {
                continue;
            }
            File override = new File(layoutDir, xmlName);
            if (override.isFile()) {
                continue;
            }
            out.add(new ProjectFileTreeAdapter.Node(ProjectFileTreeAdapter.TYPE_GENERATED,
                    xmlName, override.getAbsolutePath(), layoutDir.getAbsolutePath(),
                    ProjectFileTreeAdapter.KIND_LAYOUT, false, depth + 1, false, -1));
        }
    }

    private int countRecursive(@NonNull File dir) {
        int count = 0;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                count += child.isDirectory() ? countRecursive(child) : 1;
            }
        }
        return count;
    }

    /** Whether {@code path} is the tree root or one of the category roots. */
    boolean isTreeRoot(@NonNull String path) {
        return path.equals(scDataFilesPath);
    }

    @NonNull
    private String toScRelative(@NonNull String absolutePath) {
        if (absolutePath.startsWith(scDataFilesPath)) {
            String relative = absolutePath.substring(scDataFilesPath.length());
            if (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            return "files/" + relative;
        }
        return absolutePath;
    }

    //endregion

    //region Node interactions

    @Override
    public void onNodeClicked(@NonNull ProjectFileTreeAdapter.Node node) {
        if (node.type == ProjectFileTreeAdapter.TYPE_DIRECTORY) {
            // Expand/collapse folders directly in the adapter.
            boolean nowExpanded = !adapter.isExpanded(node.path);
            adapter.setExpanded(node.path, nowExpanded);
            adapter.submitModel(adapter.currentModel());
            return;
        }
        if (node.type == ProjectFileTreeAdapter.TYPE_GENERATED) {
            if (node.title.equals("AndroidManifest.xml")) {
                viewManifest();
            } else {
                // Generated file: read-only preview with a Customize action.
                String kind = ProjectFileTreeAdapter.KIND_LAYOUT.equals(node.generatedKind)
                        ? "layout" : "java";
                Intent intent = new Intent(getApplicationContext(), ProjectCodeEditorActivity.class);
                intent.putExtra(ProjectCodeEditorActivity.EXTRA_SC_ID, scId);
                intent.putExtra(ProjectCodeEditorActivity.EXTRA_VIEW_NAME, node.title);
                intent.putExtra(ProjectCodeEditorActivity.EXTRA_VIEW_KIND, kind);
                intent.putExtra(ProjectCodeEditorActivity.EXTRA_VIEW_TARGET, node.path);
                startActivity(intent);
            }
            return;
        }
        // Regular file: open editable. This is the primary path now.
        Intent intent = new Intent(getApplicationContext(), ProjectCodeEditorActivity.class);
        intent.putExtra(ProjectCodeEditorActivity.EXTRA_SC_ID, scId);
        intent.putExtra(ProjectCodeEditorActivity.EXTRA_OPEN_PATH, node.path);
        startActivity(intent);
    }

    @Override
    public void onNodeMenuRequested(@NonNull View anchor, @NonNull ProjectFileTreeAdapter.Node node) {
        showNodeActionsDialog(node);
    }

    @Override
    public void onExpansionChanged() {
        // Expansion is handled inline in onNodeClicked.
    }

    //endregion

    //region Node actions (all Material dialogs)

    private void showNodeActionsDialog(@NonNull ProjectFileTreeAdapter.Node node) {
        List<String> actions = new ArrayList<>();
        List<Runnable> runners = new ArrayList<>();
        if (node.path.isEmpty()) {
            // Virtual entry (e.g. manifest): only the actions that make sense for it.
            actions.add(getString(R.string.file_explorer_menu_view_manifest));
            runners.add(this::viewManifest);
            actions.add(getString(R.string.file_explorer_menu_edit_manifest));
            runners.add(this::openManifestEditor);
        } else if (node.type == ProjectFileTreeAdapter.TYPE_DIRECTORY) {
            actions.add(getString(R.string.file_explorer_menu_new_file));
            runners.add(() -> promptCreateFile(new File(node.path), true));
            actions.add(getString(R.string.file_explorer_menu_new_folder));
            runners.add(() -> promptCreateFile(new File(node.path), false));
            if (!isTreeRoot(node.path) && new File(node.path).listFiles() != null
                    && new File(node.path).listFiles().length == 0) {
                actions.add(getString(R.string.common_word_delete));
                runners.add(() -> confirmDelete(new File(node.path), true));
            } else {
                actions.add(getString(R.string.file_explorer_menu_paste_into));
                runners.add(() -> pasteInto(new File(node.path)));
            }
        } else {
            actions.add(getString(R.string.common_word_edit));
            runners.add(() -> onNodeClicked(node));
            actions.add(getString(R.string.file_explorer_menu_duplicate));
            runners.add(() -> promptDuplicate(new File(node.path)));
            actions.add(getString(R.string.common_word_copy));
            runners.add(() -> {
                clipboardPath = node.path;
                clipboardOp = ClipboardOp.COPY;
                SketchwareUtil.toast(getString(R.string.file_explorer_copied_toast, node.title));
            });
            actions.add(getString(R.string.common_word_cut));
            runners.add(() -> {
                clipboardPath = node.path;
                clipboardOp = ClipboardOp.CUT;
                SketchwareUtil.toast(getString(R.string.file_explorer_cut_toast, node.title));
            });
            actions.add(getString(R.string.file_explorer_menu_import_here));
            runners.add(() -> startImport(new File(node.path).getParentFile()));
            if (node.type == ProjectFileTreeAdapter.TYPE_GENERATED) {
                actions.add(getString(R.string.file_explorer_menu_view_generated));
                runners.add(() -> onNodeClicked(node));
                actions.add(getString(R.string.file_explorer_menu_customize));
                runners.add(() -> customizeGenerated(node));
            } else {
                if (isRegisterableSource(node)) {
                    boolean registered = frc.getJavaManifestList().contains(registeredClassName(node))
                            || frc.getServiceManifestList().contains(registeredClassName(node));
                    actions.add(getString(registered
                            ? R.string.file_explorer_menu_manifest_remove_activity
                            : R.string.file_explorer_menu_manifest_add_activity));
                    runners.add(() -> registerInManifest(node, !registered));
                }
                if (catalog.findByOverride(toScRelative(node.path)) != null) {
                    // This user file overrides a block-generated file.
                    actions.add(getString(R.string.file_explorer_menu_reset_generated));
                    runners.add(() -> confirmResetToGenerated(node));
                }
                actions.add(getString(R.string.common_word_rename));
                runners.add(() -> promptRename(node));
                actions.add(getString(R.string.common_word_delete));
                runners.add(() -> confirmDelete(new File(node.path), false));
            }
        }
        actions.add(getString(R.string.common_word_cancel));

        new MaterialAlertDialogBuilder(this)
                .setTitle(node.title)
                .setItems(actions.toArray(new String[0]), (dialog, which) -> {
                    if (which < runners.size()) {
                        runners.get(which).run();
                    }
                })
                .show();
    }

    private void promptCreateFile(@NonNull File targetDir, boolean isFile) {
        View dialogRoot = getLayoutInflater().inflate(R.layout.dialog_project_file_input, null);
        TextInputLayout layout = dialogRoot.findViewById(R.id.input_layout);
        TextInputEditText input = dialogRoot.findViewById(R.id.input);
        layout.setHint(isFile
                ? getString(R.string.file_explorer_hint_file_name)
                : getString(R.string.file_explorer_hint_folder_name));
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(isFile ? R.string.file_explorer_menu_new_file : R.string.file_explorer_menu_new_folder)
                .setView(dialogRoot)
                .setPositiveButton(R.string.common_word_create, null)
                .setNegativeButton(R.string.common_word_cancel, null)
                .create();
        dialog.setOnShowListener(dialogInterface -> {
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setOnClickListener(v -> {
                String name = Helper.getText(input);
                String error = validateName(name, targetDir);
                if (error != null) {
                    layout.setError(error);
                    return;
                }
                File target = new File(targetDir, name);
                boolean ok = isFile ? createTextFile(target) : target.mkdirs();
                if (ok) {
                    dialog.dismiss();
                    refresh();
                    if (adapter.getExpandedPaths().contains(targetDir.getAbsolutePath())) {
                        // keep expanded; refresh() re-applies state
                    }
                    if (isFile) {
                        launchEditor(target.getAbsolutePath());
                    }
                } else {
                    layout.setError(getString(R.string.file_explorer_error_create_failed));
                }
            });
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            input.requestFocus();
        });
        dialog.show();
    }

    private void promptRename(@NonNull ProjectFileTreeAdapter.Node node) {
        View dialogRoot = getLayoutInflater().inflate(R.layout.dialog_project_file_input, null);
        TextInputLayout layout = dialogRoot.findViewById(R.id.input_layout);
        TextInputEditText input = dialogRoot.findViewById(R.id.input);
        layout.setHint(getString(R.string.file_explorer_hint_new_name));
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(node.title);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.common_word_rename)
                .setView(dialogRoot)
                .setPositiveButton(R.string.common_word_rename, null)
                .setNegativeButton(R.string.common_word_cancel, null)
                .create();
        dialog.setOnShowListener(dialogInterface -> {
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setOnClickListener(v -> {
                String name = Helper.getText(input);
                if (name.equals(node.title)) {
                    dialog.dismiss();
                    return;
                }
                String error = validateName(name, new File(node.parentPath));
                if (error != null) {
                    layout.setError(error);
                    return;
                }
                File destination = new File(node.parentPath, name);
                if (FileUtil.renameFile(node.path, destination.getAbsolutePath())) {
                    // Rename customization records too (override file moved).
                    ProjectFileCatalog.Entry entry = catalog.findByOverride(toScRelative(node.path));
                    if (entry != null) {
                        catalog.removeByOverride(entry.overridePath);
                        catalog.markCustomized(toScRelative(destination.getAbsolutePath()), "", name);
                    }
                    dialog.dismiss();
                    refresh();
                } else {
                    layout.setError(getString(R.string.file_explorer_error_rename_failed));
                }
            });
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            input.requestFocus();
        });
        dialog.show();
    }

    private void promptDuplicate(@NonNull File source) {
        View dialogRoot = getLayoutInflater().inflate(R.layout.dialog_project_file_input, null);
        TextInputLayout layout = dialogRoot.findViewById(R.id.input_layout);
        TextInputEditText input = dialogRoot.findViewById(R.id.input);
        layout.setHint(getString(R.string.file_explorer_hint_copy_name));
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(suggestDuplicateName(source));
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.file_explorer_menu_duplicate)
                .setView(dialogRoot)
                .setPositiveButton(R.string.common_word_create, null)
                .setNegativeButton(R.string.common_word_cancel, null)
                .create();
        dialog.setOnShowListener(dialogInterface -> {
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setOnClickListener(v -> {
                String name = Helper.getText(input);
                String error = validateName(name, source.getParentFile());
                if (error != null) {
                    layout.setError(error);
                    return;
                }
                File target = new File(source.getParentFile(), name);
                if (copyRecursively(source, target)) {
                    dialog.dismiss();
                    refresh();
                    SketchwareUtil.toast(getString(R.string.file_explorer_duplicated_toast, name));
                } else {
                    layout.setError(getString(R.string.file_explorer_error_create_failed));
                }
            });
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            input.requestFocus();
        });
        dialog.show();
    }

    private void confirmDelete(@NonNull File target, boolean isDirectory) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.common_word_delete))
                .setMessage(getString(R.string.file_explorer_delete_message,
                        target.getName() + (isDirectory ? "/" : "")))
                .setPositiveButton(R.string.common_word_delete, (dialog, which) -> {
                    FileUtil.deleteFile(target.getAbsolutePath());
                    catalog.removeByOverride(toScRelative(target.getAbsolutePath()));
                    SketchwareUtil.toast(Helper.getResString(R.string.common_word_deleted_successfully));
                    refresh();
                })
                .setNegativeButton(R.string.common_word_cancel, null)
                .show();
    }

    private void pasteInto(@NonNull File targetDir) {
        if (clipboardPath == null) {
            SketchwareUtil.toast(getString(R.string.file_explorer_clipboard_empty));
            return;
        }
        File source = new File(clipboardPath);
        if (!source.exists()) {
            SketchwareUtil.toastError(getString(R.string.file_explorer_clipboard_gone));
            clipboardPath = null;
            return;
        }
        if (isAncestorOf(source, targetDir)) {
            SketchwareUtil.toastError(getString(R.string.file_explorer_paste_invalid));
            return;
        }
        File target = new File(targetDir, uniqueChildName(targetDir, source.getName()));
        if (copyRecursively(source, target)) {
            if (clipboardOp == ClipboardOp.CUT) {
                FileUtil.deleteFile(source.getAbsolutePath());
                clipboardPath = null;
            }
            refresh();
        } else {
            SketchwareUtil.toastError(getString(R.string.file_explorer_error_create_failed));
        }
    }

    //endregion

    //region Import (system file picker)

    private void startImport(@Nullable File targetDir) {
        importTargetDir = targetDir != null ? targetDir.getAbsolutePath()
                : new File(scDataFilesPath, "java").getAbsolutePath();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(Intent.createChooser(intent,
                    getString(R.string.file_explorer_menu_import)), 4201);
        } catch (android.content.ActivityNotFoundException e) {
            SketchwareUtil.toastError("No file picker available");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 4201 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importUri(data.getData());
        }
    }

    private void importUri(@NonNull Uri uri) {
        File targetDir = new File(importTargetDir != null
                ? importTargetDir : new File(scDataFilesPath, "java").getAbsolutePath());
        String name = queryDisplayName(uri);
        if (name == null || name.isEmpty()) {
            name = "imported_" + System.currentTimeMillis() + ".txt";
        }
        name = uniqueChildName(targetDir, name);
        File target = new File(targetDir, name);
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) {
                throw new IOException("Cannot open " + uri);
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } catch (IOException | SecurityException e) {
            SketchwareUtil.toastError("Import failed: " + e.getMessage());
            return;
        }
        refresh();
        SketchwareUtil.toast(getString(R.string.file_explorer_imported_toast, name));
    }

    @Nullable
    private String queryDisplayName(@NonNull Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return cursor.getString(idx);
                }
            }
        } catch (Exception ignored) {
        }
        return uri.getLastPathSegment();
    }

    //endregion

    //region Generated-file customization (Code Mode ↔ Block Mode bridge)

    private void confirmResetToGenerated(@NonNull ProjectFileTreeAdapter.Node node) {
        ProjectFileCatalog.Entry entry = catalog.findByOverride(toScRelative(node.path));
        if (entry == null) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.file_explorer_menu_reset_generated)
                .setMessage(getString(R.string.file_explorer_reset_message, entry.title))
                .setPositiveButton(R.string.common_word_delete, (dialog, which) -> {
                    FileUtil.deleteFile(node.path);
                    catalog.removeByOverride(entry.overridePath);
                    SketchwareUtil.toast(getString(R.string.file_explorer_reset_toast));
                    refresh();
                })
                .setNegativeButton(R.string.common_word_cancel, null)
                .show();
    }

    private void customizeGenerated(@NonNull ProjectFileTreeAdapter.Node node) {
        if (node.path.isEmpty()) {
            return;
        }
        File target = new File(node.path);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.file_explorer_menu_customize)
                .setMessage(getString(R.string.file_explorer_customize_message, node.title))
                .setPositiveButton(R.string.file_explorer_menu_customize, (dialog, which) -> {
                    k(); // show loading dialog (base class)
                    new Thread(() -> {
                        File parent = target.getParentFile();
                        boolean ok = parent != null && (parent.exists() || parent.mkdirs());
                        String content = "";
                        if (ok) {
                            try {
                                content = new a.a.a.yq(getApplicationContext(), scId).getFileSrc(
                                        node.title, jC.b(scId), jC.a(scId), jC.c(scId));
                            } catch (Exception e) {
                                content = "";
                            }
                            FileUtil.writeFile(target.getAbsolutePath(), content);
                            ok = target.isFile();
                        }
                        boolean success = ok;
                        runOnUiThread(() -> {
                            h(); // hide loading dialog
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            if (success) {
                                catalog.markCustomized(toScRelative(target.getAbsolutePath()), "", node.title);
                                refresh();
                                launchEditor(target.getAbsolutePath());
                            } else {
                                SketchwareUtil.toastError("Could not customize " + node.title);
                            }
                        });
                    }, "CustomizeGenerated").start();
                })
                .setNegativeButton(R.string.common_word_cancel, null)
                .show();
    }

    private void viewManifest() {
        Intent intent = new Intent(getApplicationContext(), com.besome.sketch.common.SrcViewerActivity.class);
        intent.putExtra("sc_id", scId);
        intent.putExtra("current", "AndroidManifest.xml");
        startActivity(intent);
    }

    /** Opens the app's existing manifest editor on the first project activity. */
    private void openManifestEditor() {
        String firstActivity = "MainActivity";
        try {
            hC projectFileManager = jC.b(scId);
            List<ProjectFileBean> beans = projectFileManager == null ? null : projectFileManager.b();
            if (beans != null && !beans.isEmpty() && beans.get(0).getJavaName() != null) {
                firstActivity = beans.get(0).getJavaName();
            }
        } catch (Exception ignored) {
        }
        Intent intent = new Intent(getApplicationContext(),
                mod.hilal.saif.activities.android_manifest.AndroidManifestInjection.class);
        intent.putExtra("sc_id", scId);
        intent.putExtra("file_name", firstActivity);
        startActivity(intent);
    }

    //endregion

    //region Helpers

    @Nullable
    private String validateName(@Nullable String name, @NonNull File targetDir) {
        if (name == null || name.trim().isEmpty()) {
            return getString(R.string.file_explorer_error_name_required);
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            return getString(R.string.file_explorer_error_name_invalid);
        }
        if (new File(targetDir, name).exists()) {
            return getString(R.string.file_explorer_error_exists);
        }
        return null;
    }

    @NonNull
    private static String suggestDuplicateName(@NonNull File source) {
        String name = source.getName();
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        return base + "_copy" + ext;
    }

    @NonNull
    private static String uniqueChildName(@NonNull File dir, @NonNull String desired) {
        if (!new File(dir, desired).exists()) {
            return desired;
        }
        String base = desired;
        String ext = "";
        int dot = desired.lastIndexOf('.');
        if (dot > 0) {
            base = desired.substring(0, dot);
            ext = desired.substring(dot);
        }
        for (int i = 1; i < 1000; i++) {
            String candidate = base + "_" + i + ext;
            if (!new File(dir, candidate).exists()) {
                return candidate;
            }
        }
        return "copy_" + System.currentTimeMillis() + "_" + desired;
    }

    private static boolean isAncestorOf(@NonNull File ancestor, @NonNull File candidate) {
        String a = ancestor.getAbsolutePath();
        String c = candidate.getAbsolutePath();
        return c.startsWith(a.endsWith(File.separator) ? a : a + File.separator);
    }

    private static boolean copyRecursively(@NonNull File source, @NonNull File target) {
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs()) {
                return false;
            }
            File[] children = source.listFiles();
            boolean ok = children != null;
            if (children != null) {
                for (File child : children) {
                    ok &= copyRecursively(child, new File(target, child.getName()));
                }
            }
            return ok;
        }
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return true;
        } catch (IOException e) {
            FileUtil.deleteFile(target.getAbsolutePath());
            return false;
        }
    }

    private boolean createTextFile(@NonNull File target) {
        String name = target.getName().toLowerCase();
        String template;
        if (name.endsWith(".java")) {
            String simpleName = name.substring(0, name.length() - 5);
            template = "public class " + simpleName + " {\n\n}\n";
        } else if (name.endsWith(".kt")) {
            String simpleName = name.substring(0, name.length() - 3);
            template = "class " + simpleName + " {\n\n}\n";
        } else if (name.endsWith(".xml")) {
            template = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";
        } else {
            template = "";
        }
        FileUtil.writeFile(target.getAbsolutePath(), template);
        return target.isFile();
    }

    private void launchEditor(@NonNull String path) {
        Intent intent = new Intent(getApplicationContext(), ProjectCodeEditorActivity.class);
        intent.putExtra(ProjectCodeEditorActivity.EXTRA_SC_ID, scId);
        intent.putExtra(ProjectCodeEditorActivity.EXTRA_OPEN_PATH, path);
        startActivity(intent);
    }

    //endregion

    //region Manifest registration (custom activities/services)

    private boolean isRegisterableSource(@NonNull ProjectFileTreeAdapter.Node node) {
        return node.type == ProjectFileTreeAdapter.TYPE_FILE
                && node.path.startsWith(new File(scDataFilesPath, "java").getAbsolutePath())
                && (node.title.endsWith(".java") || node.title.endsWith(".kt"));
    }

    @NonNull
    private String registeredClassName(@NonNull ProjectFileTreeAdapter.Node node) {
        String javaRoot = new File(scDataFilesPath, "java").getAbsolutePath();
        String pkgRoot = readPackageName(node.path);
        if (pkgRoot != null) {
            return pkgRoot + "." + FileUtil.getFileNameNoExtension(node.path);
        }
        String relative = node.path.startsWith(javaRoot)
                ? node.path.substring(javaRoot.length()) : "";
        if (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        int slash = relative.lastIndexOf('/');
        String folderPackage = slash > 0 ? relative.substring(0, slash).replace('/', '.') : "";
        String packageName = getIntent().getStringExtra("pkgName");
        String base = packageName != null && !packageName.isEmpty() ? packageName : "";
        String full = folderPackage.isEmpty() ? base : base.isEmpty() ? folderPackage : base + "." + folderPackage;
        return (full.isEmpty() ? "" : full + ".") + FileUtil.getFileNameNoExtension(node.path);
    }

    @Nullable
    private String readPackageName(@NonNull String filePath) {
        try {
            String content = FileUtil.readFileIfExist(filePath);
            if (content.isEmpty()) {
                return null;
            }
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("package\\s+([\\w.]+)\\s*;").matcher(content);
            return matcher.find() ? matcher.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void registerInManifest(@NonNull ProjectFileTreeAdapter.Node node, boolean add) {
        String className = registeredClassName(node);
        boolean asService = false; // Activity registration only; services live in the components manager.
        List<String> activities = frc.getJavaManifestList();
        if (add && !activities.contains(className)) {
            activities.add(className);
        } else if (!add) {
            activities.remove(className);
        }
        FileUtil.writeFile(new FilePathUtil().getManifestJava(scId), getGson().toJson(activities));
        SketchwareUtil.toast((add ? "Added " : "Removed ")
                + FileUtil.getFileNameNoExtension(node.title) + (asService ? "" : " to manifest"));
    }

    //endregion

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        getMenuInflater().inflate(R.menu.project_file_explorer_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_refresh) {
            refresh();
            return true;
        } else if (itemId == R.id.action_new_file) {
            promptCreateFile(new File(scDataFilesPath, "java"), true);
            return true;
        } else if (itemId == R.id.action_new_folder) {
            promptCreateFolderInRoot();
            return true;
        } else if (itemId == R.id.action_import) {
            startImport(null);
            return true;
        } else if (itemId == R.id.action_open_editor) {
            startActivity(new Intent(getApplicationContext(), ProjectCodeEditorActivity.class)
                    .putExtra(ProjectCodeEditorActivity.EXTRA_SC_ID, scId));
            return true;
        } else if (itemId == R.id.action_view_all_sources) {
            Intent intent = new Intent(getApplicationContext(), com.besome.sketch.common.SrcViewerActivity.class);
            intent.putExtra("sc_id", scId);
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void promptCreateFolderInRoot() {
        // Offer creation inside the first-level category roots.
        String[] roots = {"java", "resource", "assets"};
        String[] labels = {"java", "resource", "assets"};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.file_explorer_menu_new_folder)
                .setItems(labels, (dialog, which) ->
                        promptCreateFile(new File(scDataFilesPath, roots[which]), false))
                .setNegativeButton(R.string.common_word_cancel, null)
                .show();
    }
}
