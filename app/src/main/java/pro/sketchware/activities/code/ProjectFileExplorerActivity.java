package pro.sketchware.activities.code;

import static pro.sketchware.utility.GsonUtils.getGson;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.PopupMenu;

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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * Code Mode entry point: a real file tree of the Sketchware Pro project being edited.
 * <p>
 * The tree is built from the same metadata the block editor and the built-in source
 * viewer use ({@link jC} managers), so it shows every activity and layout of the
 * project immediately - even before anything was generated on disk. Entries backed
 * by block generation open as read-only previews; "Customize" materializes the file
 * into the user-owned directory that the build pipeline treats as authoritative:
 * <ul>
 *     <li><b>java</b> - custom Java/Kotlin sources, compiled by kotlinc + ECJ,</li>
 *     <li><b>resource</b> - res overrides compiled by aapt2 (values, drawable, ...),</li>
 *     <li><b>assets</b> - packaged into the APK as-is.</li>
 * </ul>
 * Sketchware Pro's own application sources and tooling internals are not exposed.
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

        adapter = new ProjectFileTreeAdapter(new ProjectFileTreeAdapter.Listener() {
            @Override
            public void onItemClicked(@NonNull ProjectFileTreeAdapter.Item item) {
                ProjectFileExplorerActivity.this.onItemClicked(item);
            }

            @Override
            public void onItemMenuRequested(@NonNull View anchor, @NonNull ProjectFileTreeAdapter.Item item) {
                ProjectFileExplorerActivity.this.onItemMenuRequested(anchor, item);
            }
        });
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

        binding.openEditorButton.setOnClickListener(v -> launchEditor(null));

        enterDirectory(currentDirectory);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_CURRENT_DIR, currentDirectory);
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

    private void enterDirectory(@NonNull String path) {
        currentDirectory = path;
        refresh();
    }

    //region Tree building

    private void refresh() {
        catalog.pruneMissing();
        List<ProjectFileTreeAdapter.Item> items = new ArrayList<>();

        if (rootDirectory.equals(currentDirectory)) {
            buildRootTree(items);
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
        binding.toolbar.setSubtitle(shown.isEmpty() ? null : shown);
    }

    /**
     * Builds the root view: manifest, activities, layouts, resources and other
     * source directories, merged from project metadata and the user-owned disk tree.
     */
    private void buildRootTree(@NonNull List<ProjectFileTreeAdapter.Item> items) {
        List<ProjectFileBean> activities = new ArrayList<>();
        List<ProjectFileBean> customViews = new ArrayList<>();
        boolean metadataAvailable = loadProjectBeans(activities, customViews);

        File javaDir = new File(scDataFilesPath, "java");
        File layoutDir = new File(scDataFilesPath, "resource/layout");
        File resourceDir = new File(scDataFilesPath, "resource");
        Set<String> shownPaths = new HashSet<>();

        // Manifest (view + edit via the app's manifest editor)
        items.add(ProjectFileTreeAdapter.header(getString(R.string.file_explorer_section_manifest)));
        items.add(ProjectFileTreeAdapter.generated("", "AndroidManifest.xml",
                ProjectFileTreeAdapter.KIND_MANIFEST, null, false));

        // Java: one entry per block-mode activity, then custom java/kotlin files
        List<ProjectFileTreeAdapter.Item> javaItems = new ArrayList<>();
        for (ProjectFileBean bean : activities) {
            String javaName = bean.getJavaName();
            if (javaName == null || javaName.isEmpty()) {
                continue;
            }
            File override = new File(javaDir, javaName);
            if (override.isFile()) {
                javaItems.add(ProjectFileTreeAdapter.file(override.getAbsolutePath(), javaName, true));
            } else {
                javaItems.add(ProjectFileTreeAdapter.generated("", javaName,
                        ProjectFileTreeAdapter.KIND_ACTIVITY, override.getAbsolutePath(), false));
            }
            shownPaths.add(override.getAbsolutePath());
        }
        appendDiskFiles(javaItems, javaDir, javaDir, ".java", shownPaths);
        appendDiskFiles(javaItems, javaDir, javaDir, ".kt", shownPaths);
        appendDiskFiles(javaItems, javaDir, javaDir, ".kts", shownPaths);
        if (!javaItems.isEmpty()) {
            items.add(ProjectFileTreeAdapter.header(getString(R.string.file_explorer_section_java)));
            items.addAll(javaItems);
        }

        // Layouts: block-mode layouts, then custom xml layouts
        List<ProjectFileTreeAdapter.Item> layoutItems = new ArrayList<>();
        List<ProjectFileBean> layoutBeans = new ArrayList<>(activities);
        layoutBeans.addAll(customViews);
        for (ProjectFileBean bean : layoutBeans) {
            String xmlName = bean.getXmlName();
            if (xmlName == null || xmlName.isEmpty()) {
                continue;
            }
            File override = new File(layoutDir, xmlName);
            if (override.isFile()) {
                layoutItems.add(ProjectFileTreeAdapter.file(override.getAbsolutePath(), xmlName, true));
            } else {
                layoutItems.add(ProjectFileTreeAdapter.generated("", xmlName,
                        ProjectFileTreeAdapter.KIND_LAYOUT, override.getAbsolutePath(), false));
            }
            shownPaths.add(override.getAbsolutePath());
        }
        appendDiskFiles(layoutItems, layoutDir, layoutDir, ".xml", shownPaths);
        if (!layoutItems.isEmpty()) {
            items.add(ProjectFileTreeAdapter.header(getString(R.string.file_explorer_section_layouts)));
            items.addAll(layoutItems);
        }

        // Other resources (values, drawable, ...): browsable directories
        List<ProjectFileTreeAdapter.Item> resourceItems = new ArrayList<>();
        if (metadataAvailable) {
            File[] resourceDirs = resourceDir.listFiles(File::isDirectory);
            if (resourceDirs != null) {
                for (File dir : resourceDirs) {
                    if (layoutDir.equals(dir)) {
                        continue;
                    }
                    int count = countFilesRecursive(dir, 0);
                    if (count > 0) {
                        resourceItems.add(ProjectFileTreeAdapter.directory(dir.getAbsolutePath(),
                                dir.getName(), count + (count == 1 ? " item" : " items")));
                    }
                }
            }
        }
        if (!resourceItems.isEmpty()) {
            items.add(ProjectFileTreeAdapter.header(getString(R.string.file_explorer_section_resources)));
            items.addAll(resourceItems);
        }

        // Assets and other source roots
        List<ProjectFileTreeAdapter.Item> otherItems = new ArrayList<>();
        appendDirectoryRow(otherItems, new File(scDataFilesPath, "assets"),
                "Files packaged into the APK");
        appendDirectoryRow(otherItems, new File(scDataFilesPath, "broadcast"),
                "Broadcast receiver sources");
        appendDirectoryRow(otherItems, new File(scDataFilesPath, "service"),
                "Service sources");
        if (!otherItems.isEmpty()) {
            items.add(ProjectFileTreeAdapter.header(getString(R.string.file_explorer_section_other)));
            items.addAll(otherItems);
        }
    }

    /**
     * Loads the block-mode activities and custom views. Never throws: a broken or
     * missing metadata store degrades to a disk-only tree.
     */
    private boolean loadProjectBeans(@NonNull List<ProjectFileBean> activities,
                                     @NonNull List<ProjectFileBean> customViews) {
        try {
            hC projectFileManager = jC.b(scId);
            if (projectFileManager == null) {
                return false;
            }
            List<ProjectFileBean> beans = projectFileManager.b();
            if (beans != null) {
                activities.addAll(beans);
            }
            List<ProjectFileBean> customs = projectFileManager.c();
            if (customs != null) {
                customViews.addAll(customs);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void appendDirectoryRow(@NonNull List<ProjectFileTreeAdapter.Item> items,
                                    @NonNull File dir, @NonNull String description) {
        if (dir.isDirectory()) {
            int count = countFilesRecursive(dir, 0);
            if (count > 0) {
                items.add(ProjectFileTreeAdapter.directory(dir.getAbsolutePath(),
                        dir.getName(), count + (count == 1 ? " item" : " items")));
            }
        }
    }

    /**
     * Appends files of {@code root} (recursively) that are not yet shown, titled by
     * their path relative to {@code root} (e.g. "utils/Foo.java").
     */
    private void appendDiskFiles(@NonNull List<ProjectFileTreeAdapter.Item> items,
                                 @NonNull File root, @NonNull File dir,
                                 @NonNull String extension, @NonNull Set<String> shownPaths) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        List<File> foundFiles = new ArrayList<>();
        List<File> subDirs = new ArrayList<>();
        for (File child : children) {
            if (child.isDirectory()) {
                subDirs.add(child);
            } else if (child.getName().toLowerCase().endsWith(extension)) {
                foundFiles.add(child);
            }
        }
        foundFiles.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        subDirs.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File file : foundFiles) {
            if (shownPaths.add(file.getAbsolutePath())) {
                boolean customized = catalog.findByOverride(toScRelative(file.getAbsolutePath())) != null;
                items.add(ProjectFileTreeAdapter.file(file.getAbsolutePath(),
                        relativize(root, file), customized));
            }
        }
        for (File subDir : subDirs) {
            appendDiskFiles(items, root, subDir, extension, shownPaths);
        }
    }

    @NonNull
    private static String relativize(@NonNull File root, @NonNull File file) {
        String rootPath = root.getAbsolutePath();
        String path = file.getAbsolutePath();
        String relative = path.startsWith(rootPath) ? path.substring(rootPath.length()) : path;
        if (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        return relative.isEmpty() ? file.getName() : relative;
    }

    private int countFilesRecursive(@NonNull File dir, int depth) {
        if (depth > 6) {
            return 0;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        int count = 0;
        for (File child : children) {
            if (child.isFile()) {
                count++;
            } else if (child.isDirectory()) {
                count += countFilesRecursive(child, depth + 1);
            }
        }
        return count;
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

    //region Item interactions

    private void onItemClicked(@NonNull ProjectFileTreeAdapter.Item item) {
        if (item.type == ProjectFileTreeAdapter.TYPE_HEADER) {
            return;
        }
        if (item.type == ProjectFileTreeAdapter.TYPE_DIRECTORY) {
            enterDirectory(item.path);
            return;
        }
        if (ProjectFileTreeAdapter.KIND_MANIFEST.equals(item.generatedKind)) {
            viewManifest();
            return;
        }
        if (item.type == ProjectFileTreeAdapter.TYPE_GENERATED && !item.isCustomized) {
            // Block-generated file: open read-only preview with a customize action.
            String kind = ProjectFileTreeAdapter.KIND_LAYOUT.equals(item.generatedKind) ? "layout" : "java";
            launchGeneratedViewer(item.title, kind, item.customizedPath);
            return;
        }
        if (item.type == ProjectFileTreeAdapter.TYPE_GENERATED && item.customizedPath != null) {
            // A customized generated entry opens the override copy.
            launchEditor(item.customizedPath);
            return;
        }
        launchEditor(item.path);
    }

    private void onItemMenuRequested(@NonNull View anchor, @NonNull ProjectFileTreeAdapter.Item item) {
        PopupMenu popup = new PopupMenu(this, anchor, Gravity.END);
        if (item.type == ProjectFileTreeAdapter.TYPE_DIRECTORY) {
            popup.getMenu().add(Menu.NONE, 1, Menu.NONE, R.string.file_explorer_menu_new_file);
            popup.getMenu().add(Menu.NONE, 2, Menu.NONE, R.string.file_explorer_menu_new_folder);
            popup.getMenu().add(Menu.NONE, 3, Menu.NONE, R.string.common_word_delete);
        } else if (ProjectFileTreeAdapter.KIND_MANIFEST.equals(item.generatedKind)) {
            popup.getMenu().add(Menu.NONE, 13, Menu.NONE, R.string.file_explorer_menu_view_manifest);
            popup.getMenu().add(Menu.NONE, 14, Menu.NONE, R.string.file_explorer_menu_edit_manifest);
        } else if (item.type == ProjectFileTreeAdapter.TYPE_GENERATED) {
            if (item.isCustomized) {
                popup.getMenu().add(Menu.NONE, 4, Menu.NONE, R.string.common_word_open);
                popup.getMenu().add(Menu.NONE, 11, Menu.NONE, R.string.file_explorer_menu_reset_generated);
            } else {
                popup.getMenu().add(Menu.NONE, 4, Menu.NONE, R.string.file_explorer_menu_view_generated);
                popup.getMenu().add(Menu.NONE, 12, Menu.NONE, R.string.file_explorer_menu_customize);
            }
        } else {
            popup.getMenu().add(Menu.NONE, 4, Menu.NONE, R.string.common_word_edit);
            if (isRegisterableSource(item)) {
                boolean registeredAsActivity = frc.getJavaManifestList().contains(registeredClassName(item));
                boolean registeredAsService = frc.getServiceManifestList().contains(registeredClassName(item));
                popup.getMenu().add(Menu.NONE, registeredAsActivity ? 6 : 5, Menu.NONE,
                        registeredAsActivity
                                ? R.string.file_explorer_menu_manifest_remove_activity
                                : R.string.file_explorer_menu_manifest_add_activity);
                popup.getMenu().add(Menu.NONE, 7, Menu.NONE,
                        registeredAsService
                                ? R.string.file_explorer_menu_manifest_remove_service
                                : R.string.file_explorer_menu_manifest_add_service);
            }
            if (catalog.findByOverride(toScRelative(item.path)) != null) {
                popup.getMenu().add(Menu.NONE, 11, Menu.NONE, R.string.file_explorer_menu_reset_generated);
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
            case 4 -> onItemClicked(item);
            case 5 -> registerInManifest(item, true, false);
            case 6 -> registerInManifest(item, false, false);
            case 7 -> registerInManifest(item, true, true);
            case 8 -> promptRename(item);
            case 9 -> confirmDelete(item.path, false);
            case 11 -> confirmResetToGenerated(item);
            case 12 -> customizeGenerated(item);
            case 13 -> viewManifest();
            case 14 -> openManifestEditor();
            default -> {
            }
        }
    }

    private void viewManifest() {
        Intent intent = new Intent(getApplicationContext(), com.besome.sketch.common.SrcViewerActivity.class);
        intent.putExtra("sc_id", scId);
        intent.putExtra("current", "AndroidManifest.xml");
        startActivity(intent);
    }

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

    //region Generated-file customization (the Code Mode ↔ Block Mode bridge)

    private void customizeGenerated(@NonNull ProjectFileTreeAdapter.Item item) {
        if (item.customizedPath == null) {
            return;
        }
        File target = new File(item.customizedPath);
        if (target.isFile()) {
            // Already customized (catalog entry may have been pruned); re-track and open.
            trackCustomization(item, target);
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.file_explorer_menu_customize)
                .setMessage(getString(R.string.file_explorer_customize_message, item.title))
                .setPositiveButton(R.string.file_explorer_menu_customize, (dialog, which) ->
                        customizeGeneratedInBackground(item, target))
                .setNegativeButton(R.string.common_word_cancel, null)
                .show();
    }

    /**
     * Materializes the generated file off the UI thread (generation can be expensive)
     * and opens the customized copy when done.
     */
    private void customizeGeneratedInBackground(@NonNull ProjectFileTreeAdapter.Item item,
                                                @NonNull File target) {
        String requestedTitle = item.title;
        k(); // show loading dialog (base class)
        new Thread(() -> {
            File parent = target.getParentFile();
            boolean ok = parent == null || parent.exists() || parent.mkdirs();
            String content = "";
            if (ok) {
                content = FileUtil.readFileIfExist(item.path);
                if (content.isEmpty()) {
                    // Nothing generated on disk yet: generate now.
                    try {
                        content = new a.a.a.yq(getApplicationContext(), scId).getFileSrc(
                                requestedTitle, jC.b(scId), jC.a(scId), jC.c(scId));
                    } catch (Exception e) {
                        content = "";
                    }
                }
                FileUtil.writeFile(target.getAbsolutePath(), content);
                ok = target.isFile();
            }
            boolean success = ok;
            runOnUiThread(() -> {
                h(); // hide loading dialog (base class)
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (success) {
                    trackCustomization(item, target);
                } else {
                    SketchwareUtil.toastError("Could not customize " + requestedTitle);
                }
            });
        }, "CustomizeGenerated").start();
    }

    private void trackCustomization(@NonNull ProjectFileTreeAdapter.Item item, @NonNull File target) {
        catalog.markCustomized(
                toScRelative(target.getAbsolutePath()),
                "",
                item.title);
        SketchwareUtil.toast(getString(R.string.file_explorer_customized_toast, item.title));
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
                .setTitle(R.string.file_explorer_menu_reset_generated)
                .setMessage(getString(R.string.file_explorer_reset_message, entry.title))
                .setPositiveButton(R.string.common_word_delete, (dialog, which) -> {
                    FileUtil.deleteFile(overridePath);
                    catalog.removeByOverride(entry.overridePath);
                    SketchwareUtil.toast(getString(R.string.file_explorer_reset_toast));
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
        layout.setHint(isFile
                ? getString(R.string.file_explorer_hint_file_name)
                : getString(R.string.file_explorer_hint_folder_name));
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(isFile ? R.string.file_explorer_menu_new_file : R.string.file_explorer_menu_new_folder)
                .setView(layout)
                .setPositiveButton(R.string.common_word_create, null)
                .setNegativeButton(R.string.common_word_cancel, null)
                .create();
        dialog.setOnShowListener(dialogInterface -> {
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setOnClickListener(v -> {
                String name = Helper.getText(input);
                if (name.isEmpty()) {
                    layout.setError(getString(R.string.file_explorer_error_name_required));
                    return;
                }
                if (name.contains("/") || name.contains("\\") || name.contains("..")) {
                    layout.setError(getString(R.string.file_explorer_error_name_invalid));
                    return;
                }
                File target = new File(targetDir, name);
                if (target.exists()) {
                    layout.setError(getString(R.string.file_explorer_error_exists));
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
                    layout.setError(getString(R.string.file_explorer_error_create_failed));
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
        layout.setHint(getString(R.string.file_explorer_hint_new_name));
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
                    layout.setError(getString(R.string.file_explorer_error_name_required));
                    return;
                }
                if (name.contains("/") || name.contains("\\") || name.contains("..")) {
                    layout.setError(getString(R.string.file_explorer_error_name_invalid));
                    return;
                }
                File target = new File(item.path).getParentFile();
                File destination = target != null ? new File(target, name) : new File(name);
                if (destination.exists()) {
                    layout.setError(getString(R.string.file_explorer_error_exists));
                    return;
                }
                if (FileUtil.renameFile(item.path, destination.getAbsolutePath())) {
                    SketchwareUtil.toast(Helper.getResString(R.string.common_word_renamed_successfully));
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

    private void confirmDelete(@NonNull String path, boolean isDirectory) {
        String name = new File(path).getName();
        new MaterialAlertDialogBuilder(this)
                .setTitle(Helper.getResString(R.string.common_word_delete))
                .setMessage(getString(R.string.file_explorer_delete_message,
                        name + (isDirectory ? "/" : "")))
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
     * package derived from the source's package declaration, else from the folder
     * path below files/java plus the project package.
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

    //region Editor launching

    private void launchEditor(@Nullable String path) {
        Intent intent = new Intent(getApplicationContext(), ProjectCodeEditorActivity.class);
        intent.putExtra(ProjectCodeEditorActivity.EXTRA_SC_ID, scId);
        if (path != null) {
            intent.putExtra(ProjectCodeEditorActivity.EXTRA_OPEN_PATH, path);
        }
        startActivity(intent);
    }

    /** Opens a read-only preview of a block-generated file with a customize action. */
    private void launchGeneratedViewer(@NonNull String name, @NonNull String kind,
                                       @Nullable String overrideTarget) {
        Intent intent = new Intent(getApplicationContext(), ProjectCodeEditorActivity.class);
        intent.putExtra(ProjectCodeEditorActivity.EXTRA_SC_ID, scId);
        intent.putExtra(ProjectCodeEditorActivity.EXTRA_VIEW_NAME, name);
        intent.putExtra(ProjectCodeEditorActivity.EXTRA_VIEW_KIND, kind);
        if (overrideTarget != null) {
            intent.putExtra(ProjectCodeEditorActivity.EXTRA_VIEW_TARGET, overrideTarget);
        }
        startActivity(intent);
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
            promptCreateFile(currentRootForCreation(), true);
            return true;
        } else if (itemId == R.id.action_new_folder) {
            promptCreateFile(currentRootForCreation(), false);
            return true;
        } else if (itemId == R.id.action_open_editor) {
            launchEditor(null);
            return true;
        } else if (itemId == R.id.action_view_all_sources) {
            Intent intent = new Intent(getApplicationContext(), com.besome.sketch.common.SrcViewerActivity.class);
            intent.putExtra("sc_id", scId);
            startActivity(intent);
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
