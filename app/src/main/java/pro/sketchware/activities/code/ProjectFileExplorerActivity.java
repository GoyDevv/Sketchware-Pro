package pro.sketchware.activities.code;

import static pro.sketchware.utility.GsonUtils.getGson;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.PopupMenu;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.besome.sketch.lib.base.BaseAppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import mod.hey.studios.util.Helper;
import pro.sketchware.R;
import pro.sketchware.databinding.ActivityProjectFileExplorerBinding;
import pro.sketchware.utility.FilePathUtil;
import pro.sketchware.utility.FileResConfig;
import pro.sketchware.utility.FileUtil;
import pro.sketchware.utility.SketchwareUtil;

/**
 * Code Mode entry point: a real file explorer over the user's Sketchware Pro project files.
 * <p>
 * Exposed trees (all under "/Internal storage/.sketchware/data/&lt;sc_id&gt;/files"):
 * <ul>
 *     <li><b>java</b> – custom Java/Kotlin sources; compiled by kotlinc + ECJ,</li>
 *     <li><b>broadcast</b> – custom BroadcastReceiver sources, compiled by ECJ,</li>
 *     <li><b>service</b> – custom Service sources, compiled by ECJ,</li>
 *     <li><b>resource</b> – user-owned res/ overrides compiled by aapt2 (values, drawable, ...),</li>
 *     <li><b>assets</b> – packaged into the APK as-is.</li>
 * </ul>
 * Sketchware Pro's own application sources, tooling internals and anything unsafe to
 * modify are deliberately not exposed.
 * <p>
 * Additionally, a "Generated sources" section lists the block-generated activities and
 * layouts (from .sketchware/mysc/&lt;sc_id&gt;/app/src/main). "Customize" copies such a file into
 * the authoritative user-owned directory; from then on the build pipeline compiles the
 * customized copy and skips regenerating it (existing Sketchware Pro behaviour), so
 * manual edits always survive. "Reset to generated" removes the override.
 */
public class ProjectFileExplorerActivity extends BaseAppCompatActivity {

    public static final String EXTRA_SC_ID = "sc_id";

    private static final String STATE_CURRENT_DIR = "current_dir";

    private ActivityProjectFileExplorerBinding binding;
    private ProjectFileTreeAdapter adapter;
    private ProjectFileCatalog catalog;
    private FileResConfig frc;
    private String scId;
    private String scDataFilesPath;
    private String currentDirectory;
    private String rootDirectory;

    private final OnBackPressedCallback backPressedCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            if (currentDirectory != null && rootDirectory != null && !currentDirectory.equals(rootDirectory)) {
                File parent = new File(currentDirectory).getParentFile();
                enterDirectory(parent != null ? parent.getAbsolutePath() : rootDirectory);
            } else {
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
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
        rootDirectory = scDataFilesPath;
        catalog = ProjectFileCatalog.load(scId);
        frc = new FileResConfig(scId);

        setSupportActionBar(binding.toolbar);
        binding.toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        getOnBackPressedDispatcher().addCallback(this, backPressedCallback);

        adapter = new ProjectFileTreeAdapter(this::onItemClicked, this::onItemLongClicked);
        binding.fileList.setLayoutManager(new LinearLayoutManager(this));
        binding.fileList.setAdapter(adapter);

        ensureDirectoryExists(new File(scDataFilesPath, "java"));
        ensureDirectoryExists(new File(scDataFilesPath, "resource"));

        if (savedInstanceState != null) {
            String saved = savedInstanceState.getString(STATE_CURRENT_DIR);
            currentDirectory = saved != null && new File(saved).isDirectory() ? saved : rootDirectory;
        } else {
            currentDirectory = rootDirectory;
        }

        binding.refreshButton.setOnClickListener(v -> refresh());
        binding.openEditorButton.setOnClickListener(v ->
                launchEditor(null));

        enterDirectory(currentDirectory);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_CURRENT_DIR, currentDirectory);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // External changes (other managers, builds) are picked up on return.
        refresh();
    }

    private void ensureDirectoryExists(@NonNull File directory) {
        if (!directory.exists() && !directory.mkdirs()) {
            SketchwareUtil.toastError("Could not create " + directory.getName());
        }
    }

    private void enterDirectory(@NonNull String path) {
        currentDirectory = path;
        refresh();
    }

    private void refresh() {
        catalog.pruneMissing();
        List<ProjectFileTreeAdapter.Item> items = new ArrayList<>();

        if (rootDirectory.equals(currentDirectory)) {
            items.add(ProjectFileTreeAdapter.header("Sources"));
            items.add(directory(new File(scDataFilesPath, "java"), "Custom Java & Kotlin sources"));
            items.add(directory(new File(scDataFilesPath, "broadcast"), "Broadcast receiver sources"));
            items.add(directory(new File(scDataFilesPath, "service"), "Service sources"));
            items.add(ProjectFileTreeAdapter.header("Resources"));
            items.add(directory(new File(scDataFilesPath, "resource"), "Res overrides (values, drawable, ...)"));
            items.add(directory(new File(scDataFilesPath, "assets"), "Assets packaged into the APK"));
            items.add(ProjectFileTreeAdapter.header("Generated sources"));
            appendGeneratedSources(items);
        } else {
            appendDirectoryContents(items, new File(currentDirectory));
        }

        adapter.submitList(items);
        boolean empty = items.isEmpty();
        binding.noFilesLayout.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.fileList.setVisibility(empty ? View.GONE : View.VISIBLE);
        backPressedCallback.setEnabled(!rootDirectory.equals(currentDirectory));
        String shown = currentDirectory.startsWith(scDataFilesPath)
                ? currentDirectory.substring(scDataFilesPath.length()) : currentDirectory;
        binding.toolbar.setSubtitle(shown.isEmpty() ? "/" : shown);
    }

    private static ProjectFileTreeAdapter.Item directory(@NonNull File file, @NonNull String description) {
        return ProjectFileTreeAdapter.directory(file.getAbsolutePath(), file.getName(), description);
    }

    private void appendDirectoryContents(@NonNull List<ProjectFileTreeAdapter.Item> items, @NonNull File dir) {
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
        for (File child : directories) {
            items.add(ProjectFileTreeAdapter.directory(child.getAbsolutePath(), child.getName(), null));
        }
        for (File child : files) {
            boolean customized = catalog.findByOverride(toScRelative(child.getAbsolutePath())) != null;
            items.add(ProjectFileTreeAdapter.file(child.getAbsolutePath(), child.getName(), customized));
        }
    }

    private void appendGeneratedSources(@NonNull List<ProjectFileTreeAdapter.Item> items) {
        File generatedRoot = new File(FileUtil.getExternalStorageDir(),
                ".sketchware/mysc/" + scId + "/app/src/main");
        File generatedJava = new File(generatedRoot, "java");
        File generatedLayout = new File(generatedRoot, "res/layout");

        int added = 0;
        if (generatedJava.isDirectory()) {
            added += appendGeneratedJava(items, generatedJava, "");
        }
        if (generatedLayout.isDirectory()) {
            File[] layouts = generatedLayout.listFiles((dir, name) -> name.endsWith(".xml"));
            if (layouts != null) {
                for (File layout : layouts) {
                    // Overrides must be a direct child of files/resource/layout for the
                    // build's generation-skip check to see them.
                    String overrideRel = "files/resource/layout/" + layout.getName();
                    items.add(ProjectFileTreeAdapter.generated(
                            layout.getAbsolutePath(),
                            layout.getName(),
                            "layout",
                            new File(scDataFilesPath, "resource/layout/" + layout.getName()).getAbsolutePath(),
                            catalog.findByOverride(overrideRel) != null));
                    added++;
                }
            }
        }
        if (added == 0) {
            items.add(ProjectFileTreeAdapter.header("Open the project in block mode once to generate sources."));
        }
    }

    private int appendGeneratedJava(@NonNull List<ProjectFileTreeAdapter.Item> items,
                                    @NonNull File dir, @NonNull String prefix) {
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        int added = 0;
        for (File child : children) {
            if (child.isDirectory()) {
                added += appendGeneratedJava(items, child, prefix + child.getName() + "/");
            } else if (child.getName().endsWith(".java")) {
                // The build skips regenerating an activity when a file of the same name
                // exists directly in files/java, so the override copy is stored flat.
                String overrideRel = "files/java/" + child.getName();
                items.add(ProjectFileTreeAdapter.generated(
                        child.getAbsolutePath(),
                        prefix + child.getName(),
                        "activity/class",
                        new File(scDataFilesPath, "java/" + child.getName()).getAbsolutePath(),
                        catalog.findByOverride(overrideRel) != null));
                added++;
            }
        }
        return added;
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

    //region Item interactions

    private void onItemClicked(@NonNull ProjectFileTreeAdapter.Item item) {
        if (item.type == ProjectFileTreeAdapter.TYPE_HEADER) {
            return;
        }
        if (item.type == ProjectFileTreeAdapter.TYPE_DIRECTORY) {
            enterDirectory(item.path);
            return;
        }
        if (item.isGenerated && !item.isCustomized) {
            promptCustomizeGenerated(item);
            return;
        }
        if (item.isGenerated && item.customizedPath != null) {
            // A customized generated entry opens the override copy.
            launchEditor(item.customizedPath);
            return;
        }
        launchEditor(item.path);
    }

    private void onItemLongClicked(@NonNull View view, @NonNull ProjectFileTreeAdapter.Item item) {
        PopupMenu popup = new PopupMenu(this, view, Gravity.END);
        if (item.type == ProjectFileTreeAdapter.TYPE_DIRECTORY) {
            popup.getMenu().add(Menu.NONE, 1, Menu.NONE, "New file");
            popup.getMenu().add(Menu.NONE, 2, Menu.NONE, "New folder");
            popup.getMenu().add(Menu.NONE, 3, Menu.NONE, R.string.common_word_delete);
        } else if (item.isGenerated) {
            if (item.isCustomized) {
                popup.getMenu().add(Menu.NONE, 10, Menu.NONE, "Open customized file");
                popup.getMenu().add(Menu.NONE, 11, Menu.NONE, "Reset to generated");
            } else {
                popup.getMenu().add(Menu.NONE, 12, Menu.NONE, "Customize");
            }
        } else {
            popup.getMenu().add(Menu.NONE, 4, Menu.NONE, "Edit");
            if (isRegisterableSource(item)) {
                boolean registered = frc.getJavaManifestList().contains(registeredClassName(item))
                        || frc.getServiceManifestList().contains(registeredClassName(item));
                popup.getMenu().add(Menu.NONE, registered ? 6 : 5, Menu.NONE,
                        registered ? "Remove from manifest" : "Add as Activity to manifest");
                popup.getMenu().add(Menu.NONE, 7, Menu.NONE,
                        frc.getServiceManifestList().contains(registeredClassName(item))
                                ? "Remove from services" : "Add as Service to manifest");
            }
            if (catalog.findByOverride(toScRelative(item.path)) != null) {
                popup.getMenu().add(Menu.NONE, 11, Menu.NONE, "Reset to generated");
            }
            popup.getMenu().add(Menu.NONE, 8, Menu.NONE, R.string.common_word_rename);
            popup.getMenu().add(Menu.NONE, 9, Menu.NONE, R.string.common_word_delete);
        }
        popup.setOnMenuItemClickListener(menuItem -> {
            handlePopupAction(menuItem.getItemId(), item);
            return true;
        });
        popup.show();
    }

    private void handlePopupAction(int action, @NonNull ProjectFileTreeAdapter.Item item) {
        switch (action) {
            case 1 -> promptCreateFile(new File(item.path), true);
            case 2 -> promptCreateFile(new File(item.path), false);
            case 3 -> confirmDelete(item.path, true);
            case 4 -> launchEditor(item.path);
            case 5 -> registerInManifest(item, true, false);
            case 6 -> registerInManifest(item, false, false);
            case 7 -> registerInManifest(item, true, true);
            case 8 -> promptRename(item);
            case 9 -> confirmDelete(item.path, false);
            case 10 -> launchEditor(item.customizedPath != null ? item.customizedPath : item.path);
            case 11 -> confirmResetToGenerated(item);
            case 12 -> customizeGenerated(item);
            default -> {
            }
        }
    }

    //endregion

    //region Generated-file customization (the Code Mode ↔ Block Mode bridge)

    private void promptCustomizeGenerated(@NonNull ProjectFileTreeAdapter.Item item) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Customize " + item.title)
                .setMessage("Copy this generated file into your project sources and edit it freely. "
                        + "The block editor stops regenerating this file while the customized copy exists; "
                        + "use \"Reset to generated\" to return to block mode output.")
                .setPositiveButton("Customize", (dialog, which) -> customizeGenerated(item))
                .setNegativeButton(R.string.common_word_cancel, null)
                .show();
    }

    private void customizeGenerated(@NonNull ProjectFileTreeAdapter.Item item) {
        if (item.customizedPath == null) {
            return;
        }
        File target = new File(item.customizedPath);
        if (target.exists()) {
            // Already customized (catalog entry may have been pruned); re-track and open.
            trackCustomization(item, target);
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            SketchwareUtil.toastError("Could not create target directory");
            return;
        }
        String content = FileUtil.readFileIfExist(item.path);
        FileUtil.writeFile(target.getAbsolutePath(), content);
        trackCustomization(item, target);
    }

    private void trackCustomization(@NonNull ProjectFileTreeAdapter.Item item, @NonNull File target) {
        String kind = "activity/class".equals(item.generatedKind) ? "activity" : item.generatedKind;
        catalog.markCustomized(
                toScRelative(target.getAbsolutePath()),
                toScRelative(item.path),
                item.title);
        if ("activity".equals(kind) && target.getName().endsWith(".java")) {
            String className = registeredClassName(ProjectFileTreeAdapter.file(
                    target.getAbsolutePath(), target.getName(), true));
            if (!frc.getJavaManifestList().contains(className)) {
                frc.getJavaManifestList().add(className);
                FileUtil.writeFile(new FilePathUtil().getManifestJava(scId), getGson().toJson(frc.listJavaManifest));
            }
        }
        SketchwareUtil.toast("Customized " + item.title);
        refresh();
        launchEditor(target.getAbsolutePath());
    }

    private void confirmResetToGenerated(@NonNull ProjectFileTreeAdapter.Item item) {
        String overridePath = item.customizedPath != null ? item.customizedPath : item.path;
        ProjectFileCatalog.Entry entry = catalog.findByOverride(toScRelative(overridePath));
        if (entry == null) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Reset to generated")
                .setMessage("Delete the customized " + entry.title
                        + " and let the block editor generate it again?")
                .setPositiveButton(R.string.common_word_delete, (dialog, which) -> {
                    FileUtil.deleteFile(overridePath);
                    catalog.removeByOverride(entry.overridePath);
                    SketchwareUtil.toast("Reset to generated");
                    refresh();
                })
                .setNegativeButton(R.string.common_word_cancel, null)
                .show();
    }

    //endregion

    //region File operations

    private void promptCreateFile(@NonNull File targetDir, boolean isFile) {
        ViewGroup dialogRoot = (ViewGroup) getLayoutInflater()
                .inflate(R.layout.dialog_project_file_input, null);
        TextInputLayout layout = dialogRoot.findViewById(R.id.input_layout);
        TextInputEditText input = dialogRoot.findViewById(R.id.input);
        if (isFile) {
            layout.setHint("File name (e.g. MyClass.java, layout.xml)");
            input.setInputType(InputType.TYPE_CLASS_TEXT);
        } else {
            layout.setHint("Folder name");
            input.setInputType(InputType.TYPE_CLASS_TEXT);
        }
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(isFile ? "New file" : "New folder")
                .setView(layout)
                .setPositiveButton(R.string.common_word_create, null)
                .setNegativeButton(R.string.common_word_cancel, null)
                .create();
        dialog.setOnShowListener(dialogInterface -> {
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setOnClickListener(v -> {
                String name = Helper.getText(input);
                if (name.isEmpty()) {
                    layout.setError("Enter a name");
                    return;
                }
                if (name.contains("/") || name.contains("\\") || name.contains("..")) {
                    layout.setError("Invalid name");
                    return;
                }
                File target = new File(targetDir, name);
                if (target.exists()) {
                    layout.setError("Already exists");
                    return;
                }
                boolean ok = isFile ? createTextFile(target) : target.mkdirs();
                if (ok) {
                    SketchwareUtil.toast((isFile ? "File" : "Folder") + " created");
                    dialog.dismiss();
                    refresh();
                    if (isFile) {
                        launchEditor(target.getAbsolutePath());
                    }
                } else {
                    layout.setError("Could not create");
                }
            });
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            input.requestFocus();
        });
        dialog.show();
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
        return target.exists();
    }

    private void promptRename(@NonNull ProjectFileTreeAdapter.Item item) {
        ViewGroup dialogRoot = (ViewGroup) getLayoutInflater()
                .inflate(R.layout.dialog_project_file_input, null);
        TextInputLayout layout = dialogRoot.findViewById(R.id.input_layout);
        TextInputEditText input = dialogRoot.findViewById(R.id.input);
        layout.setHint("New name");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(item.title);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.common_word_rename)
                .setView(layout)
                .setPositiveButton(R.string.common_word_rename, null)
                .setNegativeButton(R.string.common_word_cancel, null)
                .create();
        dialog.setOnShowListener(dialogInterface -> {
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setOnClickListener(v -> {
                String name = Helper.getText(input);
                if (name.isEmpty()) {
                    layout.setError("Enter a name");
                    return;
                }
                if (name.contains("/") || name.contains("\\") || name.contains("..")) {
                    layout.setError("Invalid name");
                    return;
                }
                File target = new File(item.path).getParentFile();
                File destination = target != null ? new File(target, name) : new File(name);
                if (destination.exists()) {
                    layout.setError("Already exists");
                    return;
                }
                if (FileUtil.renameFile(item.path, destination.getAbsolutePath())) {
                    SketchwareUtil.toast(Helper.getResString(R.string.common_word_renamed_successfully));
                    dialog.dismiss();
                    refresh();
                } else {
                    layout.setError("Rename failed");
                }
            });
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            input.requestFocus();
        });
        dialog.show();
    }

    private void confirmDelete(@NonNull String path, boolean isDirectory) {
        String name = new File(path).getName();
        new MaterialAlertDialogBuilder(this)
                .setTitle(Helper.getResString(R.string.common_word_delete))
                .setMessage("Delete " + name + (isDirectory ? " and everything inside it?" : "?")
                        + " This cannot be undone.")
                .setPositiveButton(R.string.common_word_delete, (dialog, which) -> {
                    FileUtil.deleteFile(path);
                    catalog.removeByOverride(toScRelative(path));
                    SketchwareUtil.toast(Helper.getResString(R.string.common_word_deleted_successfully));
                    refresh();
                })
                .setNegativeButton(R.string.common_word_cancel, null)
                .show();
    }

    //endregion

    //region Manifest registration (custom activities/services)

    private boolean isRegisterableSource(@NonNull ProjectFileTreeAdapter.Item item) {
        return item.path.startsWith(new File(scDataFilesPath, "java").getAbsolutePath())
                && (item.title.endsWith(".java") || item.title.endsWith(".kt"));
    }

    /**
     * Builds the fully-qualified class name the manifest generator expects:
     * package derived from the folder path below files/java, plus the file's simple name.
     */
    @NonNull
    private String registeredClassName(@NonNull ProjectFileTreeAdapter.Item item) {
        String javaRoot = new File(scDataFilesPath, "java").getAbsolutePath();
        String pkgRoot = readPackageName(item.path);
        if (pkgRoot != null) {
            return pkgRoot + "." + FileUtil.getFileNameNoExtension(item.path);
        }
        String relative = item.path.startsWith(javaRoot)
                ? item.path.substring(javaRoot.length()) : "";
        if (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        int slash = relative.lastIndexOf('/');
        String folderPackage = slash > 0 ? relative.substring(0, slash).replace('/', '.') : "";
        String packageName = getIntent().getStringExtra("pkgName");
        String base = packageName != null && !packageName.isEmpty() ? packageName : "";
        String full = folderPackage.isEmpty() ? base : base.isEmpty() ? folderPackage : base + "." + folderPackage;
        return (full.isEmpty() ? "" : full + ".") + FileUtil.getFileNameNoExtension(item.path);
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

    private void registerInManifest(@NonNull ProjectFileTreeAdapter.Item item,
                                    boolean add, boolean asService) {
        String className = registeredClassName(item);
        if (asService) {
            List<String> services = frc.getServiceManifestList();
            if (add && !services.contains(className)) {
                services.add(className);
            } else if (!add) {
                services.remove(className);
            }
            FileUtil.writeFile(new FilePathUtil().getManifestService(scId), getGson().toJson(services));
        } else {
            List<String> activities = frc.getJavaManifestList();
            if (add && !activities.contains(className)) {
                activities.add(className);
            } else if (!add) {
                activities.remove(className);
            }
            FileUtil.writeFile(new FilePathUtil().getManifestJava(scId), getGson().toJson(activities));
        }
        SketchwareUtil.toast((add ? "Added " : "Removed ") + FileUtil.getFileNameNoExtension(item.title)
                + (asService ? " as Service" : " as Activity"));
    }

    //endregion

    private void launchEditor(@Nullable String path) {
        Intent intent = new Intent(getApplicationContext(), ProjectCodeEditorActivity.class);
        intent.putExtra(ProjectCodeEditorActivity.EXTRA_SC_ID, scId);
        if (path != null) {
            intent.putExtra(ProjectCodeEditorActivity.EXTRA_OPEN_PATH, path);
        }
        startActivity(intent);
    }

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
            promptCreateFile(currentRootForCreation(), true);
            return true;
        } else if (itemId == R.id.action_new_folder) {
            promptCreateFile(currentRootForCreation(), false);
            return true;
        } else if (itemId == R.id.action_open_editor) {
            launchEditor(null);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @NonNull
    private File currentRootForCreation() {
        return rootDirectory.equals(currentDirectory)
                ? new File(scDataFilesPath, "java") : new File(currentDirectory);
    }
}
