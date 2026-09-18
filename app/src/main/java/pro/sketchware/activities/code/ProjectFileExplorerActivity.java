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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import mod.hey.studios.util.Helper;
import pro.sketchware.R;
import pro.sketchware.databinding.ActivityProjectFileExplorerBinding;
import pro.sketchware.utility.FilePathUtil;
import pro.sketchware.utility.FileResConfig;
import pro.sketchware.utility.FileUtil;
import pro.sketchware.utility.SketchwareUtil;
import pro.sketchware.utility.UI;

/**
 * Code Mode entry point: the file tree of the project that is open in Sketchware Pro.
 * <p>
 * The tree is rooted at the project's real Android project directory
 * ({@code .sketchware/mysc/&lt;sc_id&gt;}) - the very directory a build compiles: it holds
 * {@code app/src/main/java/&lt;package&gt;}, {@code res/}, {@code assets/},
 * {@code AndroidManifest.xml} and the {@code build.gradle} files. A build regenerates that
 * directory from block metadata, so {@link ProjectWorkspace#prepare} materializes it the same
 * way, and then folds the project's user-owned files in.
 * <p>
 * Every row is a real file: nothing is virtual, and nothing is hardcoded. Files block mode owns
 * are badged, and saving one writes the project's own copy to the location the compilers read,
 * so block mode never silently overwrites a manual edit.
 */
public class ProjectFileExplorerActivity extends BaseAppCompatActivity
        implements ProjectFileTreeAdapter.Listener, ProjectFileTreeAdapter.ChildrenProvider {

    public static final String EXTRA_SC_ID = "sc_id";

    private static final String PREFS = "code_mode_explorer";
    private static final String KEY_EXPANDED = "expanded_";
    private static final String KEY_HIDDEN = "show_hidden";

    /** Packages a Java/Kotlin file declares, used to place newly created classes. */
    private static final Pattern PACKAGE_DECLARATION =
            Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;?");
    /** The file's own type declaration, used to seed a renamed class. */
    private static final Pattern TYPE_DECLARATION = Pattern.compile(
            "(?m)^(\\s*(?:public\\s+|final\\s+|abstract\\s+)*)(class|interface|enum|object|record)\\s+([A-Za-z_$][\\w$]*)");

    /** Pending clipboard file for copy/cut (cut removes the source on paste). */
    private enum ClipboardOp {COPY, CUT}

    private ActivityProjectFileExplorerBinding binding;
    private ProjectFileTreeAdapter adapter;
    /** Read from the adapter's listing thread, so it is reassigned safely. */
    private volatile ProjectWorkspace workspace;
    private FileResConfig frc;
    private SharedPreferences prefs;

    private String scId;
    private String projectName;
    private String workspaceRoot;
    private String javaRoot;

    private String clipboardPath;
    private ClipboardOp clipboardOp;
    /** Read from the adapter's listing thread. */
    private volatile boolean showHiddenFiles;
    /** Skips the redundant refresh triggered by the very first {@code onResume}. */
    private boolean firstResume = true;
    /** Guards against overlapping materializations. */
    private boolean preparing;
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

        workspace = ProjectWorkspace.load(scId);
        workspaceRoot = workspace.getRoot();
        projectName = workspace.getProjectName();
        javaRoot = new File(workspaceRoot, "app/src/main/java").getAbsolutePath();
        frc = new FileResConfig(scId);

        setSupportActionBar(binding.toolbar);
        binding.toolbar.setTitle(projectName);
        binding.toolbar.setSubtitle(getString(R.string.file_explorer_subtitle));
        binding.toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        // Window insets: the app bar paints behind the status bar and pads its own content;
        // the bottom inset keeps rows and the FAB clear of the navigation bar.
        UI.addSystemWindowInsetToPadding(binding.appBarLayout, true, true, true, false);
        UI.addSystemWindowInsetToPadding(binding.fileList, false, false, false, true);
        UI.addSystemWindowInsetToPadding(binding.noFilesLayout, false, false, false, true);
        UI.addSystemWindowInsetToMargin(binding.newFileButton, false, false, false, true);

        adapter = new ProjectFileTreeAdapter(this, this);
        adapter.setRenderListener(this::updateEmptyState);
        binding.fileList.setLayoutManager(new LinearLayoutManager(this));
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setAddDuration(180L);
        animator.setRemoveDuration(160L);
        animator.setMoveDuration(200L);
        animator.setChangeDuration(160L);
        animator.setSupportsChangeAnimations(false);
        binding.fileList.setItemAnimator(animator);
        binding.fileList.setAdapter(adapter);

        restoreExpandedPaths();
        adapter.setRoot(ProjectFileTreeAdapter.project(projectName, workspaceRoot));

        binding.newFileButton.setOnClickListener(v -> promptCreate(true, defaultCreateTarget()));
        binding.retryButton.setOnClickListener(v -> prepareWorkspace(true));

        if (savedInstanceState == null) {
            prepareWorkspace(false);
        }
    }

    //region Materialization

    /**
     * Makes sure the project's Android project directory on disk matches the project's
     * current metadata, then shows it. Runs off the main thread because it generates
     * every screen's source, exactly as a build does.
     */
    private void prepareWorkspace(boolean force) {
        if (preparing) {
            return;
        }
        if (!force && ProjectWorkspace.isPrepared(scId)) {
            adapter.invalidateAndReload();
            return;
        }
        preparing = true;
        binding.progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            String failure = null;
            try {
                ProjectWorkspace.prepare(getApplicationContext(), scId);
            } catch (Throwable t) {
                failure = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            }
            String reported = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || binding == null) {
                    return;
                }
                preparing = false;
                binding.progress.setVisibility(View.GONE);
                workspace = ProjectWorkspace.load(scId);
                adapter.invalidateAndReload();
                if (reported != null) {
                    SketchwareUtil.toastError(getString(R.string.file_explorer_prepare_failed, reported));
                }
            });
        }, "ProjectWorkspace").start();
    }

    /**
     * Shows the explanation only once the project root has actually been listed and
     * turned out to hold nothing, so no empty state ever flashes while loading.
     */
    private void updateEmptyState() {
        if (adapter == null || preparing) {
            return;
        }
        boolean empty = adapter.isLoaded(workspaceRoot) && !adapter.hasChildren(workspaceRoot);
        boolean blocked = empty && !new File(workspaceRoot).canRead();
        binding.noFilesLayout.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.fileList.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.emptyTitle.setText(blocked
                ? R.string.file_explorer_empty_blocked_title
                : R.string.file_explorer_empty_title);
        binding.emptyBody.setText(blocked
                ? R.string.file_explorer_empty_blocked_body
                : R.string.file_explorer_empty_body);
        binding.retryButton.setVisibility(blocked ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
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
        // Blocks may have been edited (or the project rebuilt) while this screen slept.
        prepareWorkspace(false);
    }

    //endregion

    //region Tree building (called on a background thread)

    @NonNull
    @Override
    public List<ProjectFileTreeAdapter.Node> childrenOf(@NonNull String directory) {
        List<ProjectFileTreeAdapter.Node> result = new ArrayList<>();
        File[] entries = new File(directory).listFiles();
        if (entries == null) {
            return result;
        }
        List<File> directories = new ArrayList<>();
        List<File> files = new ArrayList<>();
        for (File entry : entries) {
            String entryName = entry.getName();
            if (entryName.equals(ProjectWorkspace.INDEX_FILE_NAME)
                    || (!showHiddenFiles && entryName.startsWith("."))) {
                continue;
            }
            if (entry.isDirectory()) {
                // Build outputs, not project sources: they can be huge and tell nothing.
                if (entryName.equals("bin") || entryName.equals("gen") || entryName.equals("build")) {
                    continue;
                }
                directories.add(entry);
            } else {
                files.add(entry);
            }
        }
        directories.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        files.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        for (File child : directories) {
            File[] inner = child.listFiles();
            int visible = 0;
            if (inner != null) {
                for (File entry : inner) {
                    if (showHiddenFiles || !entry.getName().startsWith(".")) {
                        visible++;
                    }
                }
            }
            result.add(ProjectFileTreeAdapter.directory(child.getName(),
                    child.getAbsolutePath(), visible));
        }
        for (File child : files) {
            String path = child.getAbsolutePath();
            result.add(ProjectFileTreeAdapter.file(child.getName(), path,
                    workspace.isGenerated(path), workspace.isOverridden(path)));
        }
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

        if (node.isDirectory()) {
            boolean isRoot = node.path.equals(workspaceRoot);
            actions.add(getString(R.string.file_explorer_menu_new_file));
            runners.add(() -> promptCreate(true, node.path));
            actions.add(getString(R.string.file_explorer_menu_new_folder));
            runners.add(() -> promptCreate(false, node.path));
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
            if (!isRoot) {
                actions.add(getString(R.string.common_word_rename));
                runners.add(() -> promptRename(node));
                actions.add(getString(R.string.common_word_delete));
                runners.add(() -> confirmDelete(node));
            }
        } else {
            actions.add(getString(R.string.common_word_edit));
            runners.add(() -> openInEditor(node.path));
            if (node.generated) {
                actions.add(getString(R.string.file_explorer_menu_view_generated));
                runners.add(() -> viewGeneratedSource(node));
            }
            if (node.overridden) {
                actions.add(getString(R.string.file_explorer_menu_reset_generated));
                runners.add(() -> confirmResetToGenerated(node));
            }
            actions.add(getString(R.string.file_explorer_menu_duplicate));
            runners.add(() -> promptDuplicate(node));
            actions.add(getString(R.string.common_word_copy));
            runners.add(() -> copyToClipboard(node, ClipboardOp.COPY));
            actions.add(getString(R.string.common_word_cut));
            runners.add(() -> copyToClipboard(node, ClipboardOp.CUT));
            actions.add(getString(R.string.file_explorer_menu_copy_path));
            runners.add(() -> copyPathToClipboard(node));
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
            if (isRenamable(node)) {
                actions.add(getString(R.string.common_word_rename));
                runners.add(() -> promptRename(node));
            }
            actions.add(getString(R.string.common_word_delete));
            runners.add(() -> confirmDelete(node));
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
     * Creates a file or folder. The dialog lets the user pick the destination folder,
     * so anything can be created anywhere in the project from one place.
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
                // Remember where this file lives for builds, then show it.
                if (isFile) {
                    workspace.resolveOverrideFor(target.getAbsolutePath());
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

    /** All directories of the project, so anything can be created anywhere. */
    @NonNull
    private List<String> collectFolders() {
        List<String> folders = new ArrayList<>();
        folders.add(workspaceRoot);
        collectFolders(new File(workspaceRoot), folders);
        return folders;
    }

    private void collectFolders(@NonNull File dir, @NonNull List<String> out) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        List<File> directories = new ArrayList<>();
        for (File entry : entries) {
            String name = entry.getName();
            if (!entry.isDirectory() || (!showHiddenFiles && name.startsWith("."))
                    || name.equals("bin") || name.equals("gen") || name.equals("build")) {
                continue;
            }
            directories.add(entry);
        }
        directories.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File child : directories) {
            out.add(child.getAbsolutePath());
        }
        for (File child : directories) {
            collectFolders(child, out);
        }
    }

    /** New classes belong in the project's package folder. */
    @NonNull
    private String defaultCreateTarget() {
        File packageDir = new File(javaRoot, resolveAppPackageName().replace('.', File.separatorChar));
        if (packageDir.isDirectory()) {
            return packageDir.getAbsolutePath();
        }
        File javaDir = new File(javaRoot);
        return javaDir.isDirectory() ? javaRoot : workspaceRoot;
    }

    @NonNull
    private String toDisplayPath(@NonNull String absolute) {
        if (absolute.equals(workspaceRoot)) {
            return projectName;
        }
        if (absolute.startsWith(workspaceRoot + File.separator)) {
            return absolute.substring(workspaceRoot.length() + 1);
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
        File source = new File(node.path);
        File parentFile = source.getParentFile();
        if (parentFile == null) {
            return;
        }
        boolean renameType = node.name.endsWith(".java") || node.name.endsWith(".kt");
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.common_word_rename)
                .setMessage(getString(R.string.file_explorer_rename_message))
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
                // The user-owned copy (what a build actually reads) moves with the file.
                String previousOverride = workspace.overrideFor(node.path);
                File movedOverride = previousOverride != null && FileUtil.isExistFile(previousOverride)
                        ? new File(previousOverride) : null;
                workspace.forgetOverride(node.path);
                if (!FileUtil.renameFile(node.path, destination.getAbsolutePath())) {
                    layout.setError(getString(R.string.file_explorer_error_rename_failed));
                    return;
                }
                // A renamed class must keep matching its file name, or it cannot compile.
                if (renameType) {
                    renameTypeDeclaration(destination);
                }
                String newOverride = workspace.resolveOverrideFor(destination.getAbsolutePath());
                if (movedOverride != null && newOverride != null
                        && !movedOverride.getAbsolutePath().equals(newOverride)) {
                    File parentOfNew = new File(newOverride).getParentFile();
                    if (parentOfNew != null) {
                        FileUtil.makeDir(parentOfNew.getAbsolutePath());
                    }
                    FileUtil.renameFile(movedOverride.getAbsolutePath(), newOverride);
                }
                if (node.isDirectory()) {
                    adapter.forgetSubtree(node.path);
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

    /** Keeps a class/interface/enum/object name in sync with its file name after a rename. */
    private void renameTypeDeclaration(@NonNull File file) {
        try {
            String content = FileUtil.readFileIfExist(file.getAbsolutePath());
            if (content.isEmpty()) {
                return;
            }
            String simpleName = FileUtil.getFileNameNoExtension(file.getName());
            Matcher matcher = TYPE_DECLARATION.matcher(content);
            if (!matcher.find()) {
                return;
            }
            String old = matcher.group(3);
            if (old == null || old.equals(simpleName)) {
                return;
            }
            String updated = matcher.replaceFirst(Matcher.quoteReplacement(
                    matcher.group(1) + matcher.group(2) + " " + simpleName));
            // Constructors share the class name and must be renamed with it.
            updated = updated.replaceFirst("(?m)^(\\s*(?:public\\s+|private\\s+|protected\\s+)?)"
                    + Pattern.quote(old) + "(\\s*\\()", "$1" + Matcher.quoteReplacement(simpleName) + "$2");
            FileUtil.writeFile(file.getAbsolutePath(), updated);
        } catch (Throwable ignored) {
            // A file we cannot parse is left exactly as it is.
        }
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
                if (target.isFile()) {
                    workspace.resolveOverrideFor(target.getAbsolutePath());
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
        if (node.generated) {
            confirmResetToGenerated(node);
            return;
        }
        int message = node.isDirectory()
                ? R.string.file_explorer_delete_folder_message
                : R.string.file_explorer_delete_message;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.common_word_delete)
                .setMessage(getString(message, node.name))
                .setPositiveButton(R.string.common_word_delete, (dialog, which) -> {
                    deleteWithOverride(target);
                    if (node.isDirectory()) {
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

    /** Deletes a file (and its user-owned copy) or a whole directory subtree. */
    private void deleteWithOverride(@NonNull File target) {
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteWithOverride(child);
                }
            }
        } else {
            String override = workspace.overrideFor(target.getAbsolutePath());
            if (override != null && FileUtil.isExistFile(override)) {
                FileUtil.deleteFile(override);
            }
        }
        workspace.forgetOverride(target.getAbsolutePath());
        FileUtil.deleteFile(target.getAbsolutePath());
    }

    /** Remembers a file or folder for the next paste. */
    private void copyToClipboard(@NonNull ProjectFileTreeAdapter.Node node, @NonNull ClipboardOp op) {
        clipboardPath = node.path;
        clipboardOp = op;
        SketchwareUtil.toast(getString(op == ClipboardOp.CUT
                ? R.string.file_explorer_cut_toast
                : R.string.file_explorer_copied_toast, node.name));
    }

    private void copyPathToClipboard(@NonNull ProjectFileTreeAdapter.Node node) {
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(node.name, node.path));
        }
        SketchwareUtil.toast(getString(R.string.file_explorer_path_copied));
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
        if (target.isFile()) {
            workspace.resolveOverrideFor(target.getAbsolutePath());
        }
        if (clipboardOp == ClipboardOp.CUT) {
            deleteWithOverride(source);
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
        workspace.resolveOverrideFor(target.getAbsolutePath());
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

    //region Block-generated files (Code Mode <-> Block Mode bridge)

    /**
     * Shows the source block mode would generate for this file, without touching the
     * user's own copy.
     */
    private void viewGeneratedSource(@NonNull ProjectFileTreeAdapter.Node node) {
        // The generator is asked for the file by name; the target tells the editor where
        // an edit of that preview belongs, so saving still reaches the build.
        startActivity(new Intent(getApplicationContext(), ProjectCodeEditorActivity.class)
                .putExtra(ProjectCodeEditorActivity.EXTRA_SC_ID, scId)
                .putExtra(ProjectCodeEditorActivity.EXTRA_VIEW_NAME, node.name)
                .putExtra(ProjectCodeEditorActivity.EXTRA_VIEW_TARGET, node.path));
    }

    /**
     * Returns a generated file to block mode by removing the user's own copy. The
     * generated content itself is restored from project metadata.
     */
    private void confirmResetToGenerated(@NonNull ProjectFileTreeAdapter.Node node) {
        String override = workspace.overrideFor(node.path);
        if (override == null || !FileUtil.isExistFile(override)) {
            SketchwareUtil.toast(getString(R.string.file_explorer_no_own_copy));
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.file_explorer_menu_reset_generated)
                .setMessage(getString(R.string.file_explorer_reset_message, node.name))
                .setPositiveButton(R.string.common_word_delete, (dialog, which) -> {
                    FileUtil.deleteFile(override);
                    workspace.forgetOverride(node.path);
                    SketchwareUtil.toast(getString(R.string.file_explorer_reset_toast));
                    // Regenerate so the tree shows block mode's version again.
                    prepareWorkspace(true);
                })
                .setNegativeButton(R.string.common_word_cancel, null)
                .show();
    }

    //endregion

    //region Helpers

    private void openInEditor(@NonNull String path) {
        startActivity(new Intent(getApplicationContext(), ProjectCodeEditorActivity.class)
                .putExtra(ProjectCodeEditorActivity.EXTRA_SC_ID, scId)
                .putExtra(ProjectCodeEditorActivity.EXTRA_OPEN_PATH, path));
    }

    @Nullable
    private String relativeToWorkspace(@NonNull String path) {
        if (path.equals(workspaceRoot)) {
            return "";
        }
        String prefix = workspaceRoot + File.separator;
        return path.startsWith(prefix)
                ? path.substring(prefix.length()).replace(File.separatorChar, '/')
                : null;
    }

    /**
     * Whether the file may be renamed. A file block mode owns belongs to its screen, and a
     * build file's name is part of the build configuration, so neither can be renamed
     * without breaking the project.
     */
    private boolean isRenamable(@NonNull ProjectFileTreeAdapter.Node node) {
        if (node.generated) {
            return false;
        }
        String relative = relativeToWorkspace(node.path);
        if (relative == null || relative.isEmpty()) {
            return false;
        }
        return !relative.equals("build.gradle")
                && !relative.equals("settings.gradle")
                && !relative.equals("gradle.properties")
                && !relative.equals("app/build.gradle")
                && !relative.equals("app/proguard-rules.pro");
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
            String simpleName = target.getName().substring(0, target.getName().length() - 5);
            template = "package " + resolveJavaPackage(target) + ";\n\npublic class " + simpleName + " {\n\n}\n";
        } else if (name.endsWith(".kt")) {
            String simpleName = target.getName().substring(0, target.getName().length() - 3);
            template = "package " + resolveJavaPackage(target) + "\n\nclass " + simpleName + " {\n\n}\n";
        } else if (name.endsWith(".xml")) {
            template = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";
        } else {
            template = "";
        }
        FileUtil.writeFile(target.getAbsolutePath(), template);
        return target.isFile();
    }

    /** Package a new source file belongs to, derived from its folder below the java root. */
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
            Object packageName = a.a.a.lC.b(scId).get("my_sc_pkg_name");
            if (packageName instanceof String && !((String) packageName).isEmpty()) {
                return (String) packageName;
            }
        } catch (Exception ignored) {
            // Fall through to a generic package.
        }
        return "com.my.newproject";
    }

    //endregion

    //region Manifest registration (custom activities/services)

    private boolean isRegisterableSource(@NonNull ProjectFileTreeAdapter.Node node) {
        return !node.isDirectory()
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
            Matcher matcher = PACKAGE_DECLARATION.matcher(content);
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
            // A refresh means "make the tree match the project again", so it regenerates.
            prepareWorkspace(true);
            return true;
        } else if (itemId == R.id.action_new_file) {
            promptCreate(true, defaultCreateTarget());
            return true;
        } else if (itemId == R.id.action_new_folder) {
            promptCreate(false, workspaceRoot);
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
        } else if (itemId == R.id.action_manifest_editor) {
            openManifestEditor();
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

    /**
     * Opens Sketchware's manifest component screen, which manages the activities, services
     * and receivers block mode knows about. The manifest file itself can also be edited as
     * text directly from the tree.
     */
    private void openManifestEditor() {
        String firstActivity = "MainActivity";
        try {
            var beans = a.a.a.jC.b(scId).b();
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
}
