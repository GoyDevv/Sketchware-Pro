package pro.sketchware.activities.code;

import static pro.sketchware.utility.GsonUtils.getGson;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import a.a.a.jC;
import a.a.a.lC;
import mod.hey.studios.util.Helper;
import pro.sketchware.R;
import pro.sketchware.databinding.ActivityProjectFileExplorerBinding;
import pro.sketchware.utility.FilePathUtil;
import pro.sketchware.utility.FileResConfig;
import pro.sketchware.utility.FileUtil;
import pro.sketchware.utility.SketchwareUtil;
import pro.sketchware.utility.UI;

/**
 * Code Mode entry point: a real, collapsible file tree of the project that is
 * currently open in Sketchware Pro.
 * <p>
 * The tree is rooted at the project's source directory
 * ({@code .sketchware/data/&lt;sc_id&gt;/files}) - the exact directory the build
 * pipeline reads: {@code java/} is compiled by kotlinc + ECJ, {@code resource/}
 * is merged by aapt2 and {@code assets/} is packaged as-is. Folders are listed
 * from the real filesystem on demand (never hardcoded), and block-generated
 * activities/layouts are merged in as virtual entries at the position where
 * their customized copy would live, so a project that has never been built still
 * shows its true structure.
 */
public class ProjectFileExplorerActivity extends BaseAppCompatActivity
        implements ProjectFileTreeAdapter.Listener, ProjectFileTreeAdapter.ChildrenProvider {

    public static final String EXTRA_SC_ID = "sc_id";

    private static final String PREFS = "code_mode_explorer";
    private static final String KEY_EXPANDED = "expanded_";
    private static final String KEY_HIDDEN = "show_hidden";

    /** Pending clipboard file for copy/cut (cut removes the source on paste). */
    private enum ClipboardOp {COPY, CUT}

    private ActivityProjectFileExplorerBinding binding;
    private ProjectFileTreeAdapter adapter;
    /** Every source file the project is built from, generated ones included. */
    private ProjectSourceIndex sourceIndex;
    private ProjectFileCatalog catalog;
    private FileResConfig frc;
    private SharedPreferences prefs;

    private String scId;
    /** {@code .sketchware/data/<sc_id>/files}: the project's own source tree. */
    private String filesRoot;
    private String javaRoot;
    private String resourceRoot;
    private String assetsRoot;
    private String broadcastRoot;
    private String serviceRoot;
    private String projectName;

    private String clipboardPath;
    private ClipboardOp clipboardOp;
    private boolean showHiddenFiles;
    /** Skips the redundant refresh triggered by the very first {@code onResume}. */
    private boolean firstResume = true;
    /** Directory the SAF import places files into. */
    @Nullable
    private String importTargetDir;

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

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        showHiddenFiles = prefs.getBoolean(KEY_HIDDEN, false);

        FilePathUtil fpu = new FilePathUtil();
        filesRoot = new File(fpu.getPathJava(scId)).getParentFile().getAbsolutePath();
        javaRoot = fpu.getPathJava(scId);
        resourceRoot = fpu.getPathResource(scId);
        assetsRoot = fpu.getPathAssets(scId);
        broadcastRoot = fpu.getPathBroadcast(scId);
        serviceRoot = fpu.getPathService(scId);
        projectName = resolveProjectName();

        catalog = ProjectFileCatalog.load(scId);
        // Drop records for override files that no longer exist (deleted elsewhere).
        catalog.pruneMissing();
        frc = new FileResConfig(scId);
        sourceIndex = ProjectSourceIndex.create(scId);

        setSupportActionBar(binding.toolbar);
        binding.toolbar.setTitle(projectName);
        binding.toolbar.setSubtitle(getString(R.string.file_explorer_subtitle));
        binding.toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        // Window insets: the app-bar draws behind the status bar and pads its own
        // content; the bottom inset keeps rows and the FAB clear of the nav bar.
        UI.addSystemWindowInsetToPadding(binding.appBarLayout, true, true, true, false);
        UI.addSystemWindowInsetToPadding(binding.fileList, false, false, false, true);
        UI.addSystemWindowInsetToPadding(binding.noFilesLayout, false, false, false, true);
        UI.addSystemWindowInsetToMargin(binding.newFileButton, false, false, false, true);

        adapter = new ProjectFileTreeAdapter(this, this);
        adapter.setRenderListener(this::updateEmptyState);
        binding.fileList.setLayoutManager(new LinearLayoutManager(this));
        // Expansion and collapse animate instead of snapping.
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setAddDuration(180L);
        animator.setRemoveDuration(160L);
        animator.setMoveDuration(200L);
        animator.setChangeDuration(160L);
        animator.setSupportsChangeAnimations(false);
        binding.fileList.setItemAnimator(animator);
        binding.fileList.setAdapter(adapter);

        // Every one of these is read by the build (ECJ compiles files/java,
        // files/broadcast and files/service; aapt2 overlays files/resource; the
        // packager bundles files/assets), so they are the project's real layout.
        // Creating them also guarantees a meaningful tree instead of an empty screen.
        ensureDirectoryExists(new File(javaRoot));
        ensureDirectoryExists(new File(resourceRoot));
        ensureDirectoryExists(new File(assetsRoot));
        ensureDirectoryExists(new File(broadcastRoot));
        ensureDirectoryExists(new File(serviceRoot));
        for (String directory : sourceIndex.requiredDirectories()) {
            ensureDirectoryExists(new File(directory));
        }

        restoreExpandedPaths();
        adapter.setRoot(ProjectFileTreeAdapter.project(projectName, filesRoot));

        binding.newFileButton.setOnClickListener(v -> promptCreate(true, defaultCreateTarget()));

        if (savedInstanceState == null) {
            enrichSourceIndex();
        }
    }

    /**
     * Completes the tree with the files Sketchware's generator produces but that have
     * no on-disk copy yet. Runs in the background so the screen is usable immediately;
     * the metadata-derived entries are already on screen by the time it finishes.
     */
    private void enrichSourceIndex() {
        binding.progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            int added = sourceIndex.enrichFromGenerator(getApplicationContext(), scId);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || adapter == null) {
                    return;
                }
                binding.progress.setVisibility(View.GONE);
                if (added > 0) {
                    adapter.invalidateAndReload();
                }
            });
        }, "ProjectSourceIndex").start();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // Expansion state is also persisted in prefs; this covers rotation.
        if (adapter != null) {
            persistExpandedPaths();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (adapter != null) {
            persistExpandedPaths();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (adapter != null) {
            adapter.shutdown();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // finish() may already be scheduled (missing sc_id): fields are null then.
        if (adapter == null || isFinishing()) {
            return;
        }
        if (firstResume) {
            firstResume = false;
            return;
        }
        // Pick up anything another manager (or a build) changed meanwhile.
        adapter.invalidateAndReload();
    }

    /**
     * Shows the explanation only once the project root has actually been listed and
     * turned out to be empty - for example when storage access was denied and the
     * source folders could not be created. While the listing is still in flight the
     * tree stays on screen, so no empty state ever flashes during loading.
     */
    private void updateEmptyState() {
        if (adapter == null) {
            return;
        }
        boolean empty = adapter.isLoaded(filesRoot) && !adapter.hasChildren(filesRoot);
        binding.noFilesLayout.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.fileList.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void ensureDirectoryExists(@NonNull File directory) {
        if (!directory.exists() && !directory.mkdirs()) {
            SketchwareUtil.toastError("Could not create " + directory.getName());
        }
    }

    @NonNull
    private String resolveProjectName() {
        try {
            Map<String, Object> metadata = lC.b(scId);
            if (metadata != null) {
                Object name = metadata.get("my_ws_name");
                if (name instanceof String && !((String) name).isEmpty()) {
                    return (String) name;
                }
                Object appName = metadata.get("my_app_name");
                if (appName instanceof String && !((String) appName).isEmpty()) {
                    return (String) appName;
                }
            }
        } catch (Exception ignored) {
            // Fall through to the project id.
        }
        return scId;
    }

    //region Tree building (called on a background thread)

    @NonNull
    @Override
    public List<ProjectFileTreeAdapter.Node> childrenOf(@NonNull String directory) {
        List<ProjectFileTreeAdapter.Node> result = new ArrayList<>();
        List<File> directories = new ArrayList<>();
        List<File> diskFiles = new ArrayList<>();
        Set<String> diskFileNames = new LinkedHashSet<>();

        File[] entries = new File(directory).listFiles();
        if (entries != null) {
            for (File entry : entries) {
                if (!showHiddenFiles && entry.getName().startsWith(".")) {
                    continue;
                }
                if (entry.isDirectory()) {
                    directories.add(entry);
                } else {
                    diskFiles.add(entry);
                    diskFileNames.add(entry.getName());
                }
            }
        }
        directories.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        for (File child : directories) {
            File[] inner = child.listFiles();
            int childCount = inner == null ? 0 : inner.length;
            result.add(ProjectFileTreeAdapter.directory(child.getName(),
                    child.getAbsolutePath(), childCount));
        }

        // Files you own and files Sketchware generates share one alphabetical list,
        // so a generated MainActivity.java sits beside a hand-written Helper.java
        // exactly as it would in the real source tree.
        List<ProjectFileTreeAdapter.Node> files = new ArrayList<>();
        for (File child : diskFiles) {
            files.add(ProjectFileTreeAdapter.file(child.getName(), child.getAbsolutePath(),
                    catalog.findByOverride(toScRelative(child.getAbsolutePath())) != null));
        }
        for (ProjectSourceIndex.Entry entry : sourceIndex.inDirectory(directory)) {
            if (diskFileNames.contains(entry.name)) {
                continue;
            }
            files.add(ProjectFileTreeAdapter.generated(entry.name,
                    new File(directory, entry.name).getAbsolutePath(), entry.kind));
        }
        if (filesRoot.equals(directory)) {
            // The manifest is generated from project metadata and has no override
            // file, so it is edited through Sketchware's own manifest editor.
            files.add(ProjectFileTreeAdapter.generated("AndroidManifest.xml", "",
                    ProjectFileTreeAdapter.KIND_MANIFEST));
        }
        files.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        result.addAll(files);
        return result;
    }

    //endregion

    //region Expansion persistence

    private void restoreExpandedPaths() {
        Set<String> saved = prefs.getStringSet(KEY_EXPANDED + scId, null);
        if (saved == null) {
            return;
        }
        for (String path : saved) {
            adapter.setExpanded(path, true);
        }
    }

    private void persistExpandedPaths() {
        Set<String> expanded = new LinkedHashSet<>(adapter.getExpandedPaths());
        prefs.edit().putStringSet(KEY_EXPANDED + scId, expanded).apply();
    }

    //endregion

    //region Node interactions

    @Override
    public void onNodeClicked(@NonNull ProjectFileTreeAdapter.Node node) {
        if (node.isDirectory()) {
            adapter.toggle(node);
            return;
        }
        if (ProjectFileTreeAdapter.TYPE_GENERATED == node.type) {
            if (ProjectFileTreeAdapter.KIND_MANIFEST.equals(node.generatedKind)) {
                // The manifest is generated from metadata and has no override file, so
                // tapping it opens the editor that can actually change it.
                openManifestEditor();
            } else {
                openGeneratedPreview(node);
            }
            return;
        }
        openInEditor(node.path);
    }

    @Override
    public void onNodeMenuRequested(@NonNull View anchor, @NonNull ProjectFileTreeAdapter.Node node) {
        showNodeActionsDialog(node);
    }

    //endregion

    //region Node actions (Material dialogs)

    private void showNodeActionsDialog(@NonNull ProjectFileTreeAdapter.Node node) {
        List<String> actions = new ArrayList<>();
        List<Runnable> runners = new ArrayList<>();

        switch (node.type) {
            case ProjectFileTreeAdapter.TYPE_DIRECTORY -> {
                actions.add(getString(R.string.file_explorer_menu_new_file));
                runners.add(() -> promptCreate(true, node.path));
                actions.add(getString(R.string.file_explorer_menu_new_folder));
                runners.add(() -> promptCreate(false, node.path));
                if (node.path.equals(filesRoot)) {
                    actions.add(getString(R.string.file_explorer_menu_view_manifest));
                    runners.add(this::viewManifest);
                    actions.add(getString(R.string.file_explorer_menu_edit_manifest));
                    runners.add(this::openManifestEditor);
                }
                actions.add(getString(R.string.file_explorer_menu_import_here));
                runners.add(() -> startImport(node.path));
                actions.add(getString(R.string.file_explorer_menu_paste_into));
                runners.add(() -> pasteInto(new File(node.path)));
                actions.add(getString(R.string.common_word_copy));
                runners.add(() -> copyToClipboard(node, ClipboardOp.COPY));
                actions.add(getString(R.string.common_word_cut));
                runners.add(() -> copyToClipboard(node, ClipboardOp.CUT));
                actions.add(getString(R.string.file_explorer_menu_duplicate));
                runners.add(() -> promptDuplicate(node));
                if (!node.path.equals(filesRoot)) {
                    actions.add(getString(R.string.common_word_rename));
                    runners.add(() -> promptRename(node));
                    actions.add(getString(R.string.common_word_delete));
                    runners.add(() -> confirmDelete(node));
                }
            }
            case ProjectFileTreeAdapter.TYPE_GENERATED -> {
                if (ProjectFileTreeAdapter.KIND_MANIFEST.equals(node.generatedKind)) {
                    actions.add(getString(R.string.file_explorer_menu_view_manifest));
                    runners.add(this::viewManifest);
                    actions.add(getString(R.string.file_explorer_menu_edit_manifest));
                    runners.add(this::openManifestEditor);
                } else {
                    actions.add(getString(R.string.file_explorer_menu_view_generated));
                    runners.add(() -> openGeneratedPreview(node));
                    actions.add(getString(R.string.file_explorer_menu_customize));
                    runners.add(() -> customizeGenerated(node));
                }
            }
            default -> {
                actions.add(getString(R.string.common_word_edit));
                runners.add(() -> openInEditor(node.path));
                actions.add(getString(R.string.file_explorer_menu_duplicate));
                runners.add(() -> promptDuplicate(node));
                actions.add(getString(R.string.common_word_copy));
                runners.add(() -> copyToClipboard(node, ClipboardOp.COPY));
                actions.add(getString(R.string.common_word_cut));
                runners.add(() -> copyToClipboard(node, ClipboardOp.CUT));
                File parent = new File(node.path).getParentFile();
                actions.add(getString(R.string.file_explorer_menu_import_here));
                runners.add(() -> startImport(parent == null ? null : parent.getAbsolutePath()));
                if (isRegisterableSource(node)) {
                    boolean registered = frc.getJavaManifestList().contains(registeredClassName(node));
                    actions.add(getString(registered
                            ? R.string.file_explorer_menu_manifest_remove_activity
                            : R.string.file_explorer_menu_manifest_add_activity));
                    runners.add(() -> registerInManifest(node, !registered));
                }
                if (catalog.findByOverride(toScRelative(node.path)) != null) {
                    actions.add(getString(R.string.file_explorer_menu_reset_generated));
                    runners.add(() -> confirmResetToGenerated(node));
                }
                actions.add(getString(R.string.common_word_rename));
                runners.add(() -> promptRename(node));
                actions.add(getString(R.string.common_word_delete));
                runners.add(() -> confirmDelete(node));
            }
        }
        actions.add(getString(R.string.common_word_cancel));

        new MaterialAlertDialogBuilder(this)
                .setTitle(node.name)
                .setItems(actions.toArray(new String[0]), (dialog, which) -> {
                    if (which < runners.size()) {
                        runners.get(which).run();
                    }
                })
                .show();
    }

    /**
     * Creates a file or folder. The dialog lets the user pick the destination
     * folder, so anything can be created anywhere in the project from one place.
     */
    private void promptCreate(boolean isFile, @NonNull String targetDir) {
        View dialogRoot = getLayoutInflater().inflate(R.layout.dialog_project_file_create, null);
        TextInputLayout nameLayout = dialogRoot.findViewById(R.id.input_layout);
        TextInputEditText nameInput = dialogRoot.findViewById(R.id.input);
        TextInputLayout folderLayout = dialogRoot.findViewById(R.id.folder_layout);
        AutoCompleteTextView folderInput = dialogRoot.findViewById(R.id.folder_input);

        nameLayout.setHint(isFile
                ? getString(R.string.file_explorer_hint_file_name)
                : getString(R.string.file_explorer_hint_folder_name));
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT);

        List<String> folders = collectFolders();
        List<String> labels = new ArrayList<>();
        for (String folder : folders) {
            labels.add(toDisplayPath(folder));
        }
        ArrayAdapter<String> folderAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, labels);
        folderInput.setAdapter(folderAdapter);
        int preselect = folders.indexOf(targetDir);
        if (preselect < 0) {
            preselect = 0;
        }
        final int[] selected = {preselect};
        folderInput.setText(labels.get(preselect), false);
        folderInput.setOnItemClickListener((parent, view, position, id) -> selected[0] = position);
        folderLayout.setHint(getString(R.string.file_explorer_hint_folder));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(isFile ? R.string.file_explorer_menu_new_file : R.string.file_explorer_menu_new_folder)
                .setView(dialogRoot)
                .setPositiveButton(R.string.common_word_create, null)
                .setNegativeButton(R.string.common_word_cancel, null)
                .create();
        dialog.setOnShowListener(dialogInterface -> {
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setOnClickListener(v -> {
                String name = Helper.getText(nameInput).trim();
                File targetDirectory = new File(folders.get(selected[0]));
                String error = validateName(name, targetDirectory);
                if (error != null) {
                    nameLayout.setError(error);
                    return;
                }
                File target = new File(targetDirectory, name);
                boolean ok = isFile ? createTextFile(target) : target.mkdirs();
                if (!ok) {
                    nameLayout.setError(getString(R.string.file_explorer_error_create_failed));
                    return;
                }
                dialog.dismiss();
                persistExpandedPaths();
                adapter.setExpanded(targetDirectory.getAbsolutePath(), true);
                adapter.invalidate(targetDirectory.getAbsolutePath());
                if (isFile) {
                    openInEditor(target.getAbsolutePath());
                }
            });
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            nameInput.requestFocus();
        });
        dialog.show();
    }

    /** All directories below the project root, shallowest first. */
    @NonNull
    private List<String> collectFolders() {
        List<String> folders = new ArrayList<>();
        collectFolders(new File(filesRoot), folders);
        if (folders.isEmpty()) {
            folders.add(filesRoot);
        }
        return folders;
    }

    private void collectFolders(@NonNull File dir, @NonNull List<String> out) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        List<File> directories = new ArrayList<>();
        for (File entry : entries) {
            if (entry.isDirectory() && (showHiddenFiles || !entry.getName().startsWith("."))) {
                directories.add(entry);
            }
        }
        directories.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File child : directories) {
            out.add(child.getAbsolutePath());
        }
        for (File child : directories) {
            collectFolders(child, out);
        }
    }

    /** {@code files/java} - the source root - is where new classes belong. */
    @NonNull
    private String defaultCreateTarget() {
        File javaDir = new File(javaRoot);
        return javaDir.isDirectory() ? javaRoot : filesRoot;
    }

    @NonNull
    private String toDisplayPath(@NonNull String absolute) {
        if (absolute.equals(filesRoot)) {
            return projectName;
        }
        if (absolute.startsWith(filesRoot + File.separator)) {
            return absolute.substring(filesRoot.length() + 1);
        }
        return absolute;
    }

    private void promptRename(@NonNull ProjectFileTreeAdapter.Node node) {
        View dialogRoot = getLayoutInflater().inflate(R.layout.dialog_project_file_input, null);
        TextInputLayout layout = dialogRoot.findViewById(R.id.input_layout);
        TextInputEditText input = dialogRoot.findViewById(R.id.input);
        layout.setHint(getString(R.string.file_explorer_hint_new_name));
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(node.name);
        File parentFile = new File(node.path).getParentFile();
        if (parentFile == null) {
            return;
        }
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.common_word_rename)
                .setView(dialogRoot)
                .setPositiveButton(R.string.common_word_rename, null)
                .setNegativeButton(R.string.common_word_cancel, null)
                .create();
        dialog.setOnShowListener(dialogInterface -> {
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setOnClickListener(v -> {
                String name = Helper.getText(input).trim();
                if (name.equals(node.name)) {
                    dialog.dismiss();
                    return;
                }
                String error = validateName(name, parentFile);
                if (error != null) {
                    layout.setError(error);
                    return;
                }
                File destination = new File(parentFile, name);
                if (!FileUtil.renameFile(node.path, destination.getAbsolutePath())) {
                    layout.setError(getString(R.string.file_explorer_error_rename_failed));
                    return;
                }
                if (node.isDirectory()) {
                    // The old path must not keep expansion or listing cache alive.
                    adapter.forgetSubtree(node.path);
                }
                // Keep customization records pointing at the moved file.
                ProjectFileCatalog.Entry entry = catalog.findByOverride(toScRelative(node.path));
                if (entry != null) {
                    catalog.removeByOverride(entry.overridePath);
                    catalog.markCustomized(toScRelative(destination.getAbsolutePath()), "", name);
                }
                dialog.dismiss();
                adapter.setExpanded(parentFile.getAbsolutePath(), true);
                adapter.invalidateAndReload();
            });
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            input.requestFocus();
        });
        dialog.show();
    }

    private void promptDuplicate(@NonNull ProjectFileTreeAdapter.Node node) {
        File source = new File(node.path);
        File parentFile = source.getParentFile();
        if (parentFile == null) {
            return;
        }
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
                String name = Helper.getText(input).trim();
                String error = validateName(name, parentFile);
                if (error != null) {
                    layout.setError(error);
                    return;
                }
                File target = new File(parentFile, name);
                if (!copyRecursively(source, target)) {
                    layout.setError(getString(R.string.file_explorer_error_create_failed));
                    return;
                }
                dialog.dismiss();
                adapter.invalidateAndReload();
                SketchwareUtil.toast(getString(R.string.file_explorer_duplicated_toast, name));
            });
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            input.requestFocus();
        });
        dialog.show();
    }

    private void confirmDelete(@NonNull ProjectFileTreeAdapter.Node node) {
        File target = new File(node.path);
        File parentFile = target.getParentFile();
        int message = node.isDirectory()
                ? R.string.file_explorer_delete_folder_message
                : R.string.file_explorer_delete_message;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.common_word_delete)
                .setMessage(getString(message, node.name))
                .setPositiveButton(R.string.common_word_delete, (dialog, which) -> {
                    FileUtil.deleteFile(target.getAbsolutePath());
                    catalog.removeByOverride(toScRelative(target.getAbsolutePath()));
                    if (node.isDirectory()) {
                        // Nothing under the deleted folder may stay expanded or cached.
                        adapter.forgetSubtree(node.path);
                    }
                    SketchwareUtil.toast(Helper.getResString(R.string.common_word_deleted_successfully));
                    if (parentFile != null) {
                        adapter.invalidate(parentFile.getAbsolutePath());
                    } else {
                        adapter.invalidateAndReload();
                    }
                })
                .setNegativeButton(R.string.common_word_cancel, null)
                .show();
    }

    /** Remembers a file or folder for the next paste. */
    private void copyToClipboard(@NonNull ProjectFileTreeAdapter.Node node, @NonNull ClipboardOp op) {
        clipboardPath = node.path;
        clipboardOp = op;
        SketchwareUtil.toast(getString(op == ClipboardOp.CUT
                ? R.string.file_explorer_cut_toast
                : R.string.file_explorer_copied_toast, node.name));
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
        if (!copyRecursively(source, target)) {
            SketchwareUtil.toastError(getString(R.string.file_explorer_error_create_failed));
            return;
        }
        if (clipboardOp == ClipboardOp.CUT) {
            FileUtil.deleteFile(source.getAbsolutePath());
            clipboardPath = null;
        }
        adapter.setExpanded(targetDir.getAbsolutePath(), true);
        adapter.invalidate(targetDir.getAbsolutePath());
    }

    //endregion

    //region Import (system file picker)

    private void startImport(@Nullable String targetDir) {
        importTargetDir = targetDir != null ? targetDir : defaultCreateTarget();
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
        if (binding == null) {
            return;
        }
        if (requestCode == 4201 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importUri(data.getData());
        }
    }

    private void importUri(@NonNull Uri uri) {
        File targetDir = new File(importTargetDir != null ? importTargetDir : defaultCreateTarget());
        if (!targetDir.isDirectory() && !targetDir.mkdirs()) {
            SketchwareUtil.toastError(getString(R.string.file_explorer_error_create_failed));
            return;
        }
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
        adapter.setExpanded(targetDir.getAbsolutePath(), true);
        adapter.invalidate(targetDir.getAbsolutePath());
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
            // Fall back to the URI's last segment below.
        }
        return uri.getLastPathSegment();
    }

    //endregion

    //region Generated files (Code Mode <-> Block Mode bridge)

    private void openGeneratedPreview(@NonNull ProjectFileTreeAdapter.Node node) {
        Intent intent = new Intent(getApplicationContext(), ProjectCodeEditorActivity.class);
        intent.putExtra(ProjectCodeEditorActivity.EXTRA_SC_ID, scId);
        intent.putExtra(ProjectCodeEditorActivity.EXTRA_VIEW_NAME, node.name);
        intent.putExtra(ProjectCodeEditorActivity.EXTRA_VIEW_TARGET, node.path);
        startActivity(intent);
    }

    private void customizeGenerated(@NonNull ProjectFileTreeAdapter.Node node) {
        if (node.path.isEmpty()) {
            return;
        }
        File target = new File(node.path);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.file_explorer_menu_customize)
                .setMessage(getString(R.string.file_explorer_customize_message, node.name))
                .setPositiveButton(R.string.file_explorer_menu_customize, (dialog, which) -> {
                    k(); // loading dialog from the base class
                    new Thread(() -> {
                        File parent = target.getParentFile();
                        boolean ok = parent != null && (parent.exists() || parent.mkdirs());
                        if (ok) {
                            String content;
                            try {
                                content = new a.a.a.yq(getApplicationContext(), scId).getFileSrc(
                                        node.name, jC.b(scId), jC.a(scId), jC.c(scId));
                            } catch (Exception e) {
                                content = "";
                            }
                            FileUtil.writeFile(target.getAbsolutePath(), content);
                            ok = target.isFile();
                        }
                        boolean success = ok;
                        runOnUiThread(() -> {
                            h(); // hide loading dialog
                            if (isFinishing() || isDestroyed() || binding == null) {
                                return;
                            }
                            if (success) {
                                catalog.markCustomized(toScRelative(target.getAbsolutePath()),
                                        "", node.name);
                                adapter.invalidate(target.getParentFile().getAbsolutePath());
                                SketchwareUtil.toast(getString(R.string.file_explorer_customized_toast, node.name));
                                openInEditor(target.getAbsolutePath());
                            } else {
                                SketchwareUtil.toastError(getString(R.string.file_explorer_error_create_failed));
                            }
                        });
                    }, "CustomizeGenerated").start();
                })
                .setNegativeButton(R.string.common_word_cancel, null)
                .show();
    }

    private void confirmResetToGenerated(@NonNull ProjectFileTreeAdapter.Node node) {
        ProjectFileCatalog.Entry entry = catalog.findByOverride(toScRelative(node.path));
        if (entry == null) {
            return;
        }
        File parentFile = new File(node.path).getParentFile();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.file_explorer_menu_reset_generated)
                .setMessage(getString(R.string.file_explorer_reset_message, entry.title))
                .setPositiveButton(R.string.common_word_delete, (dialog, which) -> {
                    FileUtil.deleteFile(node.path);
                    catalog.removeByOverride(entry.overridePath);
                    SketchwareUtil.toast(getString(R.string.file_explorer_reset_toast));
                    if (parentFile != null) {
                        adapter.invalidate(parentFile.getAbsolutePath());
                    } else {
                        adapter.invalidateAndReload();
                    }
                })
                .setNegativeButton(R.string.common_word_cancel, null)
                .show();
    }

    private void viewManifest() {
        Intent intent = new Intent(getApplicationContext(),
                com.besome.sketch.common.SrcViewerActivity.class);
        intent.putExtra("sc_id", scId);
        intent.putExtra("current", "AndroidManifest.xml");
        startActivity(intent);
    }

    /** Opens Sketchware's manifest editor, where the manifest is actually editable. */
    private void openManifestEditor() {
        String firstActivity = "MainActivity.java";
        try {
            var manager = jC.b(scId);
            var beans = manager == null ? null : manager.b();
            if (beans != null && !beans.isEmpty() && beans.get(0).getJavaName() != null) {
                firstActivity = beans.get(0).getJavaName();
            }
        } catch (Throwable ignored) {
            // Fall back to the default activity name.
        }
        Intent intent = new Intent(getApplicationContext(),
                mod.hilal.saif.activities.android_manifest.AndroidManifestInjection.class);
        intent.putExtra("sc_id", scId);
        intent.putExtra("file_name", firstActivity);
        startActivity(intent);
    }

    //endregion

    //region Helpers

    @NonNull
    private String toScRelative(@NonNull String absolutePath) {
        if (absolutePath.startsWith(filesRoot)) {
            String relative = absolutePath.substring(filesRoot.length());
            if (relative.startsWith(File.separator)) {
                relative = relative.substring(1);
            }
            return "files/" + relative;
        }
        return absolutePath;
    }

    private void openInEditor(@NonNull String path) {
        startActivity(new Intent(getApplicationContext(), ProjectCodeEditorActivity.class)
                .putExtra(ProjectCodeEditorActivity.EXTRA_SC_ID, scId)
                .putExtra(ProjectCodeEditorActivity.EXTRA_OPEN_PATH, path));
    }

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
        String name = target.getName().toLowerCase(Locale.ROOT);
        String template;
        if (name.endsWith(".java")) {
            String simpleName = name.substring(0, name.length() - 5);
            template = "package " + resolveJavaPackage(target) + ";\n\npublic class " + simpleName + " {\n\n}\n";
        } else if (name.endsWith(".kt")) {
            String simpleName = name.substring(0, name.length() - 3);
            template = "package " + resolveJavaPackage(target) + "\n\nclass " + simpleName + " {\n\n}\n";
        } else if (name.endsWith(".xml")) {
            template = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";
        } else {
            template = "";
        }
        FileUtil.writeFile(target.getAbsolutePath(), template);
        return target.isFile();
    }

    /** Package a new source file belongs to, derived from its folder below {@code files/java}. */
    @NonNull
    private String resolveJavaPackage(@NonNull File target) {
        File parent = target.getParentFile();
        String base = resolveAppPackageName();
        if (parent == null) {
            return base;
        }
        String parentPath = parent.getAbsolutePath();
        if (!parentPath.startsWith(javaRoot)) {
            return base;
        }
        String relative = parentPath.substring(javaRoot.length());
        if (relative.startsWith(File.separator)) {
            relative = relative.substring(1);
        }
        if (relative.isEmpty()) {
            return base;
        }
        return relative.replace(File.separatorChar, '.');
    }

    /** The project's own package name, used for newly created classes. */
    @NonNull
    private String resolveAppPackageName() {
        try {
            Map<String, Object> metadata = lC.b(scId);
            if (metadata != null) {
                Object packageName = metadata.get("my_sc_pkg_name");
                if (packageName instanceof String && !((String) packageName).isEmpty()) {
                    return (String) packageName;
                }
            }
        } catch (Exception ignored) {
            // Fall through to a generic package.
        }
        return "com.my.newproject";
    }

    //endregion

    //region Manifest registration (custom activities/services)

    private boolean isRegisterableSource(@NonNull ProjectFileTreeAdapter.Node node) {
        return ProjectFileTreeAdapter.TYPE_FILE == node.type
                && node.path.startsWith(javaRoot + File.separator)
                && (node.name.endsWith(".java") || node.name.endsWith(".kt"));
    }

    @NonNull
    private String registeredClassName(@NonNull ProjectFileTreeAdapter.Node node) {
        String declared = readPackageName(node.path);
        if (declared != null && !declared.isEmpty()) {
            return declared + "." + FileUtil.getFileNameNoExtension(node.path);
        }
        String relative = node.path.startsWith(javaRoot + File.separator)
                ? node.path.substring(javaRoot.length() + 1) : node.name;
        String packagePath = relative.replace(File.separatorChar, '.');
        int dot = packagePath.lastIndexOf('.');
        String packageName = dot > 0 ? packagePath.substring(0, dot) : resolveAppPackageName();
        return packageName + "." + FileUtil.getFileNameNoExtension(node.name);
    }

    @Nullable
    private String readPackageName(@NonNull String filePath) {
        try {
            String content = FileUtil.readFileIfExist(filePath);
            if (content.isEmpty()) {
                return null;
            }
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("^\\s*package\\s+([\\w.]+)\\s*;?", java.util.regex.Pattern.MULTILINE)
                    .matcher(content);
            return matcher.find() ? matcher.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void registerInManifest(@NonNull ProjectFileTreeAdapter.Node node, boolean add) {
        String className = registeredClassName(node);
        List<String> activities = frc.getJavaManifestList();
        if (add) {
            if (!activities.contains(className)) {
                activities.add(className);
            }
        } else {
            activities.remove(className);
        }
        FileUtil.writeFile(new FilePathUtil().getManifestJava(scId), getGson().toJson(activities));
        SketchwareUtil.toast((add ? "Added " : "Removed ")
                + FileUtil.getFileNameNoExtension(node.name) + " to manifest");
    }

    //endregion

    //region Menu

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        getMenuInflater().inflate(R.menu.project_file_explorer_menu, menu);
        MenuItem hidden = menu.findItem(R.id.action_show_hidden);
        if (hidden != null) {
            hidden.setChecked(showHiddenFiles);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_refresh) {
            if (adapter != null) {
                adapter.invalidateAndReload();
            }
            return true;
        } else if (itemId == R.id.action_new_file) {
            promptCreate(true, defaultCreateTarget());
            return true;
        } else if (itemId == R.id.action_new_folder) {
            promptCreate(false, filesRoot);
            return true;
        } else if (itemId == R.id.action_import) {
            startImport(null);
            return true;
        } else if (itemId == R.id.action_open_editor) {
            startActivity(new Intent(getApplicationContext(), ProjectCodeEditorActivity.class)
                    .putExtra(ProjectCodeEditorActivity.EXTRA_SC_ID, scId));
            return true;
        } else if (itemId == R.id.action_view_all_sources) {
            Intent intent = new Intent(getApplicationContext(),
                    com.besome.sketch.common.SrcViewerActivity.class);
            intent.putExtra("sc_id", scId);
            startActivity(intent);
            return true;
        } else if (itemId == R.id.action_show_hidden) {
            showHiddenFiles = !showHiddenFiles;
            item.setChecked(showHiddenFiles);
            prefs.edit().putBoolean(KEY_HIDDEN, showHiddenFiles).apply();
            adapter.invalidateAndReload();
            return true;
        } else if (itemId == R.id.action_collapse_all) {
            Set<String> expanded = new LinkedHashSet<>(adapter.getExpandedPaths());
            for (String path : expanded) {
                adapter.setExpanded(path, false);
            }
            adapter.invalidateAndReload();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    //endregion
}
