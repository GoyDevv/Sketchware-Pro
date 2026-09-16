package pro.sketchware.activities.code;

import static pro.sketchware.utility.GsonUtils.getGson;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.besome.sketch.lib.base.BaseAppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import io.github.rosemoe.sora.event.SelectionChangeEvent;
import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.langs.java.JavaLanguage;
import io.github.rosemoe.sora.text.ContentListener;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula;
import mod.jbk.code.CodeEditorColorSchemes;
import mod.jbk.code.CodeEditorLanguages;
import mod.hey.studios.util.Helper;
import pro.sketchware.R;
import pro.sketchware.activities.preview.LayoutPreviewActivity;
import pro.sketchware.databinding.ActivityProjectCodeEditorBinding;
import pro.sketchware.databinding.ItemEditorTabBinding;
import pro.sketchware.utility.EditorUtils;
import pro.sketchware.utility.FileUtil;
import pro.sketchware.utility.SketchwareUtil;
import pro.sketchware.utility.ThemeUtils;
import pro.sketchware.utility.UI;

/**
 * Code Mode editor: edits the real project source files of a Sketchware Pro project
 * (".sketchware/data/&lt;sc_id&gt;/files/**"). Files opened here are the exact files the
 * build pipeline consumes:
 * <ul>
 *     <li>ECJ compiles files/java, files/broadcast and files/service,</li>
 *     <li>kotlinc compiles any .kt inside those directories,</li>
 *     <li>aapt2 compiles files/resource,</li>
 *     <li>files/assets are packaged as-is.</li>
 * </ul>
 * Multiple files can be open at once (tabs). Unsaved changes are preserved when
 * switching tabs, and the open-file list survives process death; unsaved content
 * is persisted on {@link #onStop()} and offered for restoration on reopen.
 * <p>
 * The activity also hosts a read-only preview mode for block-generated files
 * (activities/layouts that only exist as block metadata): the generated source is
 * shown and can be materialized into the user-owned tree via "Customize".
 */
public class ProjectCodeEditorActivity extends BaseAppCompatActivity {

    public static final String EXTRA_SC_ID = "sc_id";
    /** Optional: open this absolute path directly on launch. */
    public static final String EXTRA_OPEN_PATH = "open_path";
    /** Optional: view a generated file by name (read-only preview). */
    public static final String EXTRA_VIEW_NAME = "view_name";
    /** Optional: "java" or "layout"; describes {@link #EXTRA_VIEW_NAME}. */
    public static final String EXTRA_VIEW_KIND = "view_kind";
    /** Optional: absolute path the customized copy should be written to. */
    public static final String EXTRA_VIEW_TARGET = "view_target";

    private static final String PREFS_NAME = "project_code_editor";
    private static final String KEY_FONT_SIZE = "font_size";
    private static final String KEY_WORD_WRAP = "word_wrap";
    private static final String KEY_AUTO_COMPLETE = "auto_complete";
    private static final String KEY_SYMBOL_PAIRS = "symbol_pairs";
    private static final String KEY_STICKY_SCROLL = "sticky_scroll";
    private static final String KEY_OPEN_FILES = "open_files_";
    private static final String KEY_ACTIVE_FILE = "active_file_";
    private static final String KEY_CURSOR = "cursor_";
    private static final String KEY_DIRTY_FILES = "dirty_files_";
    private static final int DEFAULT_FONT_SIZE_SP = 14;
    private static final int MIN_FONT_SIZE_SP = 8;
    private static final int MAX_FONT_SIZE_SP = 32;
    private static final long MAX_EDITABLE_FILE_BYTES = 4L * 1024 * 1024;
    private static final int MAX_OPEN_FILES = 12;

    private ActivityProjectCodeEditorBinding binding;
    private final List<SourceEditorSession> sessions = new ArrayList<>();
    private int activeSessionIndex = -1;
    private String scId;
    private SharedPreferences prefs;
    private EditorTabsAdapter tabsAdapter;
    /** True while a programmatic {@link CodeEditor#setText} runs, so the listener ignores it. */
    private boolean applyingProgrammaticText;
    /** Non-null while a block-generated file is open in the buffer. */
    private GeneratedPreview generatedPreview;
    /** True once the buffer of a generated file was edited but not saved yet. */
    private boolean previewDirty;

    private final OnBackPressedCallback backPressedCallback = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            if (generatedPreview != null) {
                if (previewDirty) {
                    confirmDiscardGeneratedEdits();
                } else {
                    // Leaving always returns to the previously edited file.
                    dismissGeneratedPreview();
                }
                return;
            }
            mirrorActiveContent();
            if (hasAnyDirtySession()) {
                promptBeforeClosing();
            } else {
                persistSessionState();
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        enableEdgeToEdgeNoContrast();
        super.onCreate(savedInstanceState);
        binding = ActivityProjectCodeEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        scId = getIntent().getStringExtra(EXTRA_SC_ID);
        if (scId == null || scId.isEmpty()) {
            SketchwareUtil.toastError("Missing project id");
            finish();
            return;
        }
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Edge-to-edge insets: the app bar paints behind the status bar and pads its
        // own content, while the bottom inset keeps the symbol bar clear of the
        // navigation bar. Both come from the real insets, never fixed offsets.
        UI.addSystemWindowInsetToPadding(binding.appBarLayout, true, true, true, false);
        // The bottom inset is dropped while the keyboard is up, otherwise a gap
        // would sit between the symbol bar and the IME.
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            boolean imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
            v.setPadding(0, 0, 0, imeVisible ? 0 : bars.bottom);
            return insets;
        });

        setSupportActionBar(binding.toolbar);
        // Routing through the dispatcher keeps the "unsaved generated edits"
        // guard in one place for both the arrow and the system back gesture.
        binding.toolbar.setNavigationOnClickListener(v ->
                getOnBackPressedDispatcher().onBackPressed());

        getOnBackPressedDispatcher().addCallback(this, backPressedCallback);

        tabsAdapter = new EditorTabsAdapter();
        binding.editorTabs.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        binding.editorTabs.setAdapter(tabsAdapter);
        binding.editorTabs.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        configureEditor();
        binding.editor.getText().addContentListener(contentListener);
        binding.editor.subscribeEvent(SelectionChangeEvent.class, (event, unsubscribe) ->
                updateCursorPosition());
        restoreSessionState();

        String viewName = getIntent().getStringExtra(EXTRA_VIEW_NAME);
        String openPath = getIntent().getStringExtra(EXTRA_OPEN_PATH);
        boolean hasOpenPath = openPath != null && !openPath.isEmpty();
        if (viewName != null && !viewName.isEmpty() && !hasOpenPath) {
            // Read-only preview is only used when no editable file was requested.
            showGeneratedPreview(viewName,
                    getIntent().getStringExtra(EXTRA_VIEW_KIND),
                    getIntent().getStringExtra(EXTRA_VIEW_TARGET));
        }

        if (hasOpenPath) {
            int existing = indexOfSession(openPath);
            if (existing >= 0) {
                openSessionAt(existing);
            } else {
                openFile(openPath);
            }
        }

        binding.newTabButton.setOnClickListener(v -> showOpenFilePicker());
        updateEmptyState();
        // Bring up the keyboard right away so typing works from the first tap.
        if (generatedPreview == null && activeSessionIndex >= 0) {
            binding.editor.postDelayed(() -> {
                if (generatedPreview == null && activeSessionIndex >= 0 && !isFinishing()) {
                    binding.editor.requestFocus();
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(binding.editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                    }
                }
            }, 200);
        }
    }

    private void configureEditor() {
        CodeEditor editor = binding.editor;
        try {
            editor.setTypefaceText(EditorUtils.getTypeface(this));
        } catch (Exception e) {
            editor.setTypefaceText(Typeface.MONOSPACE);
        }
        editor.setTextSize(clampFontSize(prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE_SP)));
        editor.setWordwrap(prefs.getBoolean(KEY_WORD_WRAP, false));
        editor.getProps().symbolPairAutoCompletion = prefs.getBoolean(KEY_SYMBOL_PAIRS, true);
        editor.getProps().stickyScroll = prefs.getBoolean(KEY_STICKY_SCROLL, true);
        editor.setPinLineNumber(true);
        editor.setHighlightBracketPair(true);
        editor.setTabWidth(4);
        editor.getComponent(EditorAutoCompletion.class).setEnabled(prefs.getBoolean(KEY_AUTO_COMPLETE, true));

        binding.symbolInput.bindEditor(editor);
        binding.symbolInput.addSymbols(
                new String[]{"→", "(", ")", "{", "}", ";", "\"", "'", ".", ","},
                new String[]{"\t", "()", ")", "}", "{", ";", "\"", "'", ".", ","});
        binding.symbolInput.setTextColor(0xFF8E8E8E);
    }

    private final ContentListener contentListener = new ContentListener() {
        @Override
        public void beforeReplace(@NonNull io.github.rosemoe.sora.text.Content content) {
        }

        @Override
        public void afterInsert(@NonNull io.github.rosemoe.sora.text.Content content,
                                int startLine, int startColumn, int endLine, int endColumn,
                                @NonNull CharSequence insertedContent) {
            onEditorContentChanged();
        }

        @Override
        public void afterDelete(@NonNull io.github.rosemoe.sora.text.Content content,
                                int startLine, int startColumn, int endLine, int endColumn,
                                @NonNull CharSequence deletedContent) {
            onEditorContentChanged();
        }
    };

    private void onEditorContentChanged() {
        if (applyingProgrammaticText) {
            return;
        }
        if (generatedPreview != null) {
            // Unsaved copy of a generated file: make that obvious in the title bar.
            if (!previewDirty) {
                previewDirty = true;
                binding.toolbar.setSubtitle(getString(R.string.code_editor_generated_dirty_subtitle));
            }
            updateCursorPosition();
            return;
        }
        if (activeSessionIndex < 0 || activeSessionIndex >= sessions.size()) {
            return;
        }
        if (!sessions.get(activeSessionIndex).isModified()) {
            sessions.get(activeSessionIndex).markModified();
            tabsAdapter.notifyItemChanged(activeSessionIndex);
            updateTitle();
        }
        updateCursorPosition();
    }

    private void updateCursorPosition() {
        if (activeSessionIndex < 0 && generatedPreview == null) {
            binding.cursorPosition.setVisibility(View.GONE);
            return;
        }
        var cursor = binding.editor.getCursor();
        binding.cursorPosition.setVisibility(View.VISIBLE);
        binding.cursorPosition.setText(getString(R.string.code_editor_cursor_position,
                cursor.getLeftLine() + 1, cursor.getLeftColumn()));
    }

    private int clampFontSize(int size) {
        return Math.max(MIN_FONT_SIZE_SP, Math.min(MAX_FONT_SIZE_SP, size));
    }

    //region Session (tabs) management

    private int indexOfSession(@NonNull String filePath) {
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).getFilePath().equals(filePath)) {
                return i;
            }
        }
        return -1;
    }

    /** Copies the live editor text into the active session (cheap enough at defined points). */
    private void mirrorActiveContent() {
        if (activeSessionIndex >= 0 && activeSessionIndex < sessions.size()) {
            sessions.get(activeSessionIndex).setCurrentContent(binding.editor.getText().toString());
        }
    }

    private boolean openFile(@Nullable String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return false;
        }
        File file = new File(filePath);
        if (!file.exists() || file.isDirectory()) {
            SketchwareUtil.toastError("File not found: " + file.getName());
            return false;
        }
        if (file.length() > MAX_EDITABLE_FILE_BYTES) {
            SketchwareUtil.toastError("File is too large to edit (max 4 MB)");
            return false;
        }
        if (sessions.size() >= MAX_OPEN_FILES) {
            SketchwareUtil.toastError("Too many open files. Close one first.");
            return false;
        }
        int existing = indexOfSession(filePath);
        if (existing >= 0) {
            openSessionAt(existing);
            return true;
        }
        sessions.add(SourceEditorSession.open(filePath));
        tabsAdapter.notifyItemInserted(sessions.size() - 1);
        openSessionAt(sessions.size() - 1);
        persistSessionState();
        return true;
    }

    private void openSessionAt(int index) {
        if (index < 0 || index >= sessions.size()) {
            return;
        }
        if (generatedPreview != null) {
            dismissGeneratedPreview();
        }
        if (activeSessionIndex != index) {
            mirrorActiveContent();
        }
        activeSessionIndex = index;
        SourceEditorSession session = sessions.get(index);
        applyingProgrammaticText = true;
        binding.editor.setEditable(true);
        binding.editor.setText(session.getCurrentContent());
        binding.editor.setEditorLanguage(languageFor(session.getFileName()));
        applyColorSchemeFor(session.getFileName());
        applyingProgrammaticText = false;
        restoreCursorFor(session.getFilePath());
        updateTitle();
        tabsAdapter.notifyDataSetChanged();
        binding.editorTabs.smoothScrollToPosition(index);
        updateEmptyState();
        // Make sure the IME can actually come up: editor must be focusable-in-touch.
        binding.editor.setFocusable(View.FOCUSABLE);
        binding.editor.setFocusableInTouchMode(true);
        binding.editor.requestFocus();
    }

    private void closeSession(final int index) {
        if (index < 0 || index >= sessions.size()) {
            return;
        }
        SourceEditorSession session = sessions.get(index);
        boolean dirty = session.isDirty()
                || (index == activeSessionIndex && !session.getSavedContent()
                .equals(binding.editor.getText().toString()));
        if (dirty) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.common_word_warning)
                    .setMessage(session.getFileName() + " has unsaved changes.")
                    .setPositiveButton(R.string.common_word_save, (dialog, which) -> {
                        if (index == activeSessionIndex) {
                            session.setCurrentContent(binding.editor.getText().toString());
                        }
                        if (saveSession(index)) {
                            performClose(index);
                        }
                    })
                    .setNegativeButton(R.string.common_word_cancel, null)
                    .setNeutralButton(R.string.common_word_discard, (dialog, which) -> performClose(index))
                    .show();
            return;
        }
        performClose(index);
    }

    private void performClose(int index) {
        if (activeSessionIndex == index) {
            // Swap to a plain language first so the current one can be freed cleanly.
            binding.editor.setEditorLanguage(new EmptyLanguage());
            activeSessionIndex = -1;
        }
        sessions.remove(index);
        tabsAdapter.notifyItemRemoved(index);
        if (sessions.isEmpty()) {
            applyingProgrammaticText = true;
            binding.editor.setText("");
            applyingProgrammaticText = false;
            updateTitle();
            updateEmptyState();
            persistSessionState();
            return;
        }
        int next = Math.min(index, sessions.size() - 1);
        if (next < 0) {
            next = 0;
        }
        openSessionAt(next);
        persistSessionState();
    }

    private boolean saveSession(int index) {
        if (index < 0 || index >= sessions.size()) {
            return false;
        }
        SourceEditorSession session = sessions.get(index);
        if (index == activeSessionIndex) {
            session.setCurrentContent(binding.editor.getText().toString());
        }
        if (session.hasExternalModification() && !session.wasExternalChangeNotified()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("File changed on disk")
                    .setMessage(session.getFileName()
                            + " was modified outside this editor. Overwrite the external changes?")
                    .setPositiveButton(R.string.common_word_overwrite, (dialog, which) -> {
                        session.markExternalChangeNotified();
                        if (!saveSession(index)) {
                            SketchwareUtil.toastError("Failed to save " + session.getFileName());
                        }
                    })
                    .setNegativeButton(R.string.common_word_discard, (dialog, which) -> {
                        String reloaded = session.reloadFromDisk();
                        if (reloaded != null && index == activeSessionIndex) {
                            applyingProgrammaticText = true;
                            binding.editor.setText(reloaded);
                            applyingProgrammaticText = false;
                            tabsAdapter.notifyItemChanged(index);
                            updateTitle();
                        }
                    })
                    .show();
            return false;
        }
        if (session.save(session.getCurrentContent())) {
            tabsAdapter.notifyItemChanged(index);
            SketchwareUtil.toast(Helper.getResString(R.string.common_word_saved));
            updateTitle();
            return true;
        }
        return false;
    }

    private void saveAllSessions() {
        mirrorActiveContent();
        for (int i = sessions.size() - 1; i >= 0; i--) {
            if (sessions.get(i).isDirty() && !saveSession(i)) {
                return;
            }
        }
    }

    private boolean hasAnyDirtySession() {
        for (int i = 0; i < sessions.size(); i++) {
            SourceEditorSession session = sessions.get(i);
            if (i == activeSessionIndex) {
                if (!session.getSavedContent().equals(binding.editor.getText().toString())) {
                    return true;
                }
            } else if (session.isDirty()) {
                return true;
            }
        }
        return false;
    }

    private void promptBeforeClosing() {
        mirrorActiveContent();
        new MaterialAlertDialogBuilder(this)
                .setTitle(Helper.getResString(R.string.common_word_warning))
                .setMessage("Save changes before closing?")
                .setPositiveButton(Helper.getResString(R.string.common_word_save), (dialog, which) -> {
                    persistSessionState();
                    for (int i = sessions.size() - 1; i >= 0; i--) {
                        if (sessions.get(i).isDirty() && !saveSession(i)) {
                            return;
                        }
                    }
                    finish();
                })
                .setNegativeButton(R.string.common_word_discard, (dialog, which) -> {
                    persistSessionState();
                    finish();
                })
                .setNeutralButton(Helper.getResString(R.string.common_word_cancel), null)
                .show();
    }

    //endregion

    //region Generated-file preview (read-only) with customize

    /** State of the read-only generated-file preview, if one is shown. */
    private static final class GeneratedPreview {
        final String name;
        final String kind;
        @Nullable
        final String overrideTarget;

        GeneratedPreview(@NonNull String name, @Nullable String kind, @Nullable String overrideTarget) {
            this.name = name;
            this.kind = kind == null ? "java" : kind;
            this.overrideTarget = overrideTarget;
        }
    }

    /**
     * Generates a block-mode file on a worker thread and opens it in an editable
     * buffer. Saving that buffer writes it into the project's own source tree as the
     * user's copy, which also stops block mode regenerating it. Nothing is locked:
     * a generated file is immediately typeable.
     */
    private void showGeneratedPreview(@NonNull String name, @Nullable String kind,
                                      @Nullable String overrideTarget) {
        generatedPreview = new GeneratedPreview(name, kind, overrideTarget);
        previewDirty = false;
        binding.tabsRow.setVisibility(View.GONE);
        binding.editor.setEditable(true);
        binding.editor.setText("");
        binding.toolbar.setTitle(name);
        binding.toolbar.setSubtitle(getString(R.string.code_editor_generated_subtitle));
        invalidateOptionsMenu();
        updateEmptyState();
        k(); // show loading dialog (base class)

        String requestedName = name;
        new Thread(() -> {
            String content = "";
            try {
                var yq = new a.a.a.yq(getApplicationContext(), scId);
                content = yq.getFileSrc(requestedName, a.a.a.jC.b(scId), a.a.a.jC.a(scId), a.a.a.jC.c(scId));
            } catch (Exception e) {
                content = "";
            }
            String finalContent = content == null ? "" : content;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || generatedPreview == null
                        || !generatedPreview.name.equals(requestedName)) {
                    return;
                }
                h(); // hide loading dialog (base class)
                applyingProgrammaticText = true;
                binding.editor.setText(finalContent);
                applyingProgrammaticText = false;
                binding.editor.setEditorLanguage(languageFor(requestedName));
                applyColorSchemeFor(requestedName);
                binding.editor.setEditable(true);
                binding.editor.setFocusable(View.FOCUSABLE);
                binding.editor.setFocusableInTouchMode(true);
                binding.editor.requestFocus();
                updateEmptyState();
            });
        }, "GeneratedSourceLoad").start();
    }

    private void dismissGeneratedPreview() {
        generatedPreview = null;
        previewDirty = false;
        binding.editor.setEditable(true);
        invalidateOptionsMenu();
        updateEmptyState();
        if (activeSessionIndex >= 0 && activeSessionIndex < sessions.size()) {
            openSessionAt(activeSessionIndex);
        } else if (!sessions.isEmpty()) {
            openSessionAt(0);
        } else {
            applyingProgrammaticText = true;
            binding.editor.setText("");
            applyingProgrammaticText = false;
            binding.editor.setEditorLanguage(new EmptyLanguage());
            updateTitle();
        }
    }

    /** Asks what to do with an edited but unsaved generated-file buffer. */
    private void confirmDiscardGeneratedEdits() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(Helper.getResString(R.string.common_word_warning))
                .setMessage(getString(R.string.code_editor_generated_unsaved_message,
                        generatedPreview.name))
                .setPositiveButton(R.string.common_word_save, (dialog, which) -> saveGeneratedPreview())
                .setNegativeButton(R.string.common_word_discard, (dialog, which) -> dismissGeneratedPreview())
                .setNeutralButton(R.string.common_word_cancel, null)
                .show();
    }

    /**
     * Commits the edited buffer of a block-generated file into the project as the
     * user's own file. From then on it is an ordinary session, and the build reads
     * this file instead of regenerating it.
     */
    private void saveGeneratedPreview() {
        GeneratedPreview preview = generatedPreview;
        if (preview == null) {
            return;
        }
        if (preview.overrideTarget == null || preview.overrideTarget.isEmpty()) {
            SketchwareUtil.toastError(getString(R.string.code_editor_generated_no_target));
            return;
        }
        String targetPath = preview.overrideTarget;
        String title = preview.name;
        File target = new File(targetPath);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            SketchwareUtil.toastError(getString(R.string.file_explorer_error_create_failed));
            return;
        }
        if (!SourceEditorSession.writeContent(targetPath, binding.editor.getText().toString())) {
            SketchwareUtil.toastError(getString(R.string.file_explorer_error_create_failed));
            return;
        }
        ProjectFileCatalog catalog = ProjectFileCatalog.load(scId);
        catalog.markCustomized(toFilesRelative(scId, targetPath), "", title);
        SketchwareUtil.toast(getString(R.string.file_explorer_customized_toast, title));

        // Hand the buffer over to a normal session so further edits save directly.
        generatedPreview = null;
        previewDirty = false;
        binding.editor.setEditable(true);
        invalidateOptionsMenu();
        updateEmptyState();
        int existing = indexOfSession(targetPath);
        if (existing >= 0) {
            openSessionAt(existing);
        } else if (!openFile(targetPath)) {
            updateTitle();
        }
    }

    //endregion

    //region Open-file picker ("+" button)

    private void showOpenFilePicker() {
        // Recursively collect project source files under the user-owned tree.
        File root = new File(FileUtil.getExternalStorageDir(), ".sketchware/data/" + scId + "/files");
        List<String> paths = new ArrayList<>();
        collectFiles(root, paths, 0);
        if (paths.isEmpty()) {
            SketchwareUtil.toast("No files yet. Create one in the Files screen.");
            return;
        }
        paths.sort((a, b) -> a.compareToIgnoreCase(b));
        String[] labels = new String[paths.size()];
        for (int i = 0; i < paths.size(); i++) {
            String p = paths.get(i).replace('\\', '/');
            int idx = p.indexOf("/files/");
            labels[i] = idx >= 0 ? p.substring(idx + "/files/".length()) : p;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.code_editor_open_file)
                .setItems(labels, (dialog, which) -> openFile(paths.get(which)))
                .setNegativeButton(R.string.common_word_cancel, null)
                .show();
    }

    /**
     * Converts an absolute path under .sketchware/data/&lt;sc_id&gt;/files into the sc-relative
     * form the catalog uses ("files/&lt;relative&gt;"). Falls back to the absolute path.
     */
    @NonNull
    private static String toFilesRelative(@NonNull String scId, @NonNull String absolutePath) {
        String filesRoot = new File(FileUtil.getExternalStorageDir(),
                ".sketchware/data/" + scId + "/files").getAbsolutePath();
        String path = absolutePath.replace('\\', '/');
        String root = filesRoot.replace('\\', '/');
        if (path.startsWith(root)) {
            String relative = path.substring(root.length());
            if (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            return "files/" + relative;
        }
        return absolutePath;
    }

    private static void collectFiles(@NonNull File dir, @NonNull List<String> out, int depth) {
        if (depth > 6) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isFile()) {
                out.add(child.getAbsolutePath());
            } else if (child.isDirectory()) {
                collectFiles(child, out, depth + 1);
            }
        }
    }

    //endregion

    //region Language / colors

    @NonNull
    private Language languageFor(@NonNull String fileName) {
        if (fileName.endsWith(".java")) {
            try {
                return new JavaLanguage();
            } catch (Throwable t) {
                return new EmptyLanguage();
            }
        } else if (fileName.endsWith(".kt") || fileName.endsWith(".kts")) {
            return CodeEditorLanguages.loadTextMateLanguage(CodeEditorLanguages.SCOPE_NAME_KOTLIN);
        } else if (fileName.endsWith(".xml")) {
            return CodeEditorLanguages.loadTextMateLanguage(CodeEditorLanguages.SCOPE_NAME_XML);
        }
        return new EmptyLanguage();
    }

    /** Color scheme logic mirrors {@link EditorUtils#loadConfigByLanguage} (dark on R+ only). */
    private void applyColorSchemeFor(@NonNull String fileName) {
        CodeEditor editor = binding.editor;
        boolean darkTheme = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                && ThemeUtils.isDarkThemeEnabled(getApplicationContext());
        boolean textMateFile = fileName.endsWith(".xml") || fileName.endsWith(".kt") || fileName.endsWith(".kts");
        if (textMateFile) {
            editor.setColorScheme(CodeEditorColorSchemes.loadTextMateColorScheme(
                    darkTheme ? CodeEditorColorSchemes.THEME_DRACULA : CodeEditorColorSchemes.THEME_GITHUB));
        } else if (darkTheme) {
            editor.setColorScheme(new SchemeDarcula());
        } else {
            editor.setColorScheme(new EditorColorScheme());
        }
        EditorUtils.getMaterialStyledScheme(editor);
    }

    //endregion

    //region UI state

    private void updateTitle() {
        if (activeSessionIndex >= 0 && activeSessionIndex < sessions.size()) {
            SourceEditorSession session = sessions.get(activeSessionIndex);
            binding.toolbar.setTitle(session.getFileName());
            boolean dirty = session.isDirty()
                    || !session.getSavedContent().equals(binding.editor.getText().toString());
            binding.toolbar.setSubtitle(dirty ? Helper.getResString(R.string.code_editor_dirty_subtitle) : null);
        } else {
            binding.toolbar.setTitle(R.string.text_title_menu_files);
            binding.toolbar.setSubtitle(null);
        }
    }

    private void updateEmptyState() {
        boolean previewing = generatedPreview != null;
        boolean empty = sessions.isEmpty() && !previewing;
        binding.editor.setVisibility(empty ? View.GONE : View.VISIBLE);
        boolean showTabs = !previewing && !sessions.isEmpty();
        binding.tabsRow.setVisibility(showTabs ? View.VISIBLE : View.GONE);
        binding.noFilesLayout.setVisibility(empty ? View.VISIBLE : View.GONE);
        // The symbol bar belongs to the editable buffer, generated file included.
        binding.symbolInput.setVisibility(empty ? View.GONE : View.VISIBLE);
        updateCursorPosition();
    }

    //endregion

    //region Session persistence (survives process death)

    private void persistSessionState() {
        List<String> paths = new ArrayList<>();
        List<String> dirtyEntries = new ArrayList<>();
        for (int i = 0; i < sessions.size(); i++) {
            SourceEditorSession session = sessions.get(i);
            paths.add(session.getFilePath());
            boolean dirtyHere = session.isDirty()
                    || (i == activeSessionIndex
                    && !session.getSavedContent().equals(binding.editor.getText().toString()));
            if (dirtyHere) {
                String content = (i == activeSessionIndex)
                        ? binding.editor.getText().toString() : session.getCurrentContent();
                dirtyEntries.add(getGson().toJson(new DirtyEntry(session.getFilePath(), content)));
            }
        }
        String activePath = (activeSessionIndex >= 0 && activeSessionIndex < sessions.size())
                ? sessions.get(activeSessionIndex).getFilePath() : "";
        StringBuilder cursor = new StringBuilder();
        if (activeSessionIndex >= 0 && generatedPreview == null) {
            cursor.append(binding.editor.getCursor().getLeftLine())
                    .append(':').append(binding.editor.getCursor().getLeftColumn());
        }
        prefs.edit()
                .putString(KEY_OPEN_FILES + scId, getGson().toJson(paths))
                .putString(KEY_DIRTY_FILES + scId, getGson().toJson(dirtyEntries))
                .putString(KEY_ACTIVE_FILE + scId, activePath)
                .putString(KEY_CURSOR + scId, cursor.toString())
                .apply();
    }

    private void restoreSessionState() {
        applyingProgrammaticText = true;
        try {
            Type stringList = TypeToken.getParameterized(List.class, String.class).getType();
            String pathsJson = prefs.getString(KEY_OPEN_FILES + scId, null);
            if (pathsJson != null) {
                List<String> paths = getGson().fromJson(pathsJson, stringList);
                if (paths != null) {
                    for (String path : paths) {
                        if (path != null && new File(path).exists() && sessions.size() < MAX_OPEN_FILES) {
                            sessions.add(SourceEditorSession.open(path));
                        }
                    }
                }
            }
            String dirtyJson = prefs.getString(KEY_DIRTY_FILES + scId, null);
            if (dirtyJson != null) {
                List<String> dirtyList = getGson().fromJson(dirtyJson, stringList);
                if (dirtyList != null) {
                    for (String entryJson : dirtyList) {
                        DirtyEntry entry = getGson().fromJson(entryJson, DirtyEntry.class);
                        if (entry == null || entry.path == null) {
                            continue;
                        }
                        int index = indexOfSession(entry.path);
                        if (index >= 0) {
                            sessions.get(index).setCurrentContent(
                                    entry.content == null ? "" : entry.content);
                        }
                    }
                }
            }
            tabsAdapter.notifyDataSetChanged();
            String active = prefs.getString(KEY_ACTIVE_FILE + scId, "");
            int activeIndex = active.isEmpty() ? -1 : indexOfSession(active);
            if (activeIndex < 0 && !sessions.isEmpty()) {
                activeIndex = 0;
            }
            if (activeIndex >= 0) {
                SourceEditorSession session = sessions.get(activeIndex);
                binding.editor.setText(session.getCurrentContent());
                binding.editor.setEditorLanguage(languageFor(session.getFileName()));
                applyColorSchemeFor(session.getFileName());
                restoreCursorFor(session.getFilePath());
                activeSessionIndex = activeIndex;
            }
        } finally {
            applyingProgrammaticText = false;
        }
        updateTitle();
    }

    private void restoreCursorFor(@NonNull String filePath) {
        String saved = prefs.getString(KEY_CURSOR + scId, "");
        if (saved.isEmpty()) {
            return;
        }
        String activePath = (activeSessionIndex >= 0 && activeSessionIndex < sessions.size())
                ? sessions.get(activeSessionIndex).getFilePath() : "";
        if (!filePath.equals(activePath)) {
            return;
        }
        try {
            int sep = saved.indexOf(':');
            if (sep > 0) {
                int line = Integer.parseInt(saved.substring(0, sep));
                int column = Integer.parseInt(saved.substring(sep + 1));
                int maxLine = binding.editor.getText().getLineCount() - 1;
                if (line >= 0 && line <= maxLine) {
                    binding.editor.setSelection(line, Math.max(0, column));
                }
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private static final class DirtyEntry {
        final String path;
        final String content;

        DirtyEntry(String path, String content) {
            this.path = path;
            this.content = content;
        }
    }

    //endregion

    //region Menu actions

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        getMenuInflater().inflate(R.menu.project_code_editor_menu, menu);
        MenuItem wrap = menu.findItem(R.id.action_word_wrap);
        if (wrap != null) {
            wrap.setChecked(prefs.getBoolean(KEY_WORD_WRAP, false));
        }
        MenuItem autocomplete = menu.findItem(R.id.action_autocomplete);
        if (autocomplete != null) {
            autocomplete.setChecked(prefs.getBoolean(KEY_AUTO_COMPLETE, true));
        }
        MenuItem symbolPairs = menu.findItem(R.id.action_autocomplete_symbol_pair);
        if (symbolPairs != null) {
            symbolPairs.setChecked(prefs.getBoolean(KEY_SYMBOL_PAIRS, true));
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(@NonNull Menu menu) {
        boolean previewing = generatedPreview != null;
        // Actions that need one of the open tabs (find/replace, formatting, layout
        // preview) stay hidden in a generated-file buffer; everything else - above
        // all Save - works exactly as it does for a normal project file.
        int[] tabsOnly = {
                R.id.action_find_replace, R.id.action_goto_line, R.id.action_duplicate_line,
                R.id.action_format, R.id.action_font_size, R.id.action_word_wrap,
                R.id.action_autocomplete, R.id.action_autocomplete_symbol_pair,
                R.id.action_select_theme, R.id.action_layout_preview
        };
        for (int id : tabsOnly) {
            MenuItem item = menu.findItem(id);
            if (item != null) {
                item.setVisible(!previewing);
            }
        }
        MenuItem save = menu.findItem(R.id.action_save);
        if (save != null) {
            save.setVisible(previewing || activeSessionIndex >= 0);
        }
        MenuItem saveAll = menu.findItem(R.id.action_save_all);
        if (saveAll != null) {
            saveAll.setVisible(!previewing && !sessions.isEmpty());
        }
        MenuItem layoutPreview = menu.findItem(R.id.action_layout_preview);
        if (layoutPreview != null && !previewing) {
            layoutPreview.setVisible(isLayoutFile());
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @SuppressLint("NonConstantResourceId")
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_undo) {
            binding.editor.undo();
            return true;
        } else if (itemId == R.id.action_redo) {
            binding.editor.redo();
            return true;
        } else if (itemId == R.id.action_save) {
            if (generatedPreview != null) {
                saveGeneratedPreview();
            } else if (activeSessionIndex >= 0 && !saveSession(activeSessionIndex)) {
                SketchwareUtil.toastError("Could not save file");
            }
            return true;
        } else if (itemId == R.id.action_save_all) {
            saveAllSessions();
            return true;
        } else if (itemId == R.id.action_find_replace) {
            binding.editor.getSearcher().stopSearch();
            binding.editor.beginSearchMode();
            return true;
        } else if (itemId == R.id.action_goto_line) {
            showGotoLineDialog();
            return true;
        } else if (itemId == R.id.action_duplicate_line) {
            binding.editor.duplicateLine();
            return true;
        } else if (itemId == R.id.action_format) {
            binding.editor.formatCodeAsync();
            return true;
        } else if (itemId == R.id.action_font_size) {
            showFontSizeDialog();
            return true;
        } else if (itemId == R.id.action_word_wrap) {
            boolean newState = !item.isChecked();
            item.setChecked(newState);
            binding.editor.setWordwrap(newState);
            prefs.edit().putBoolean(KEY_WORD_WRAP, newState).apply();
            return true;
        } else if (itemId == R.id.action_autocomplete) {
            boolean newState = !item.isChecked();
            item.setChecked(newState);
            binding.editor.getComponent(EditorAutoCompletion.class).setEnabled(newState);
            prefs.edit().putBoolean(KEY_AUTO_COMPLETE, newState).apply();
            return true;
        } else if (itemId == R.id.action_autocomplete_symbol_pair) {
            boolean newState = !item.isChecked();
            item.setChecked(newState);
            binding.editor.getProps().symbolPairAutoCompletion = newState;
            prefs.edit().putBoolean(KEY_SYMBOL_PAIRS, newState).apply();
            return true;
        } else if (itemId == R.id.action_select_theme) {
            mod.hey.studios.code.SrcCodeEditor.showSwitchThemeDialog(this, binding.editor,
                    (dialog, which) -> {
                        mod.hey.studios.code.SrcCodeEditor.selectTheme(binding.editor, which);
                        dialog.dismiss();
                    });
            return true;
        } else if (itemId == R.id.action_layout_preview) {
            if (isLayoutFile()) {
                toLayoutPreview();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private boolean isLayoutFile() {
        if (activeSessionIndex < 0 || activeSessionIndex >= sessions.size()) {
            return false;
        }
        String path = sessions.get(activeSessionIndex).getFilePath().replace('\\', '/');
        return path.contains("/res/layout/") && path.endsWith(".xml");
    }

    private void toLayoutPreview() {
        if (activeSessionIndex < 0) {
            return;
        }
        Intent intent = new Intent(getApplicationContext(), LayoutPreviewActivity.class);
        intent.putExtra("title", sessions.get(activeSessionIndex).getFileName());
        intent.putExtra("xml", binding.editor.getText().toString());
        intent.putExtra("sc_id", scId);
        startActivity(intent);
    }

    private void showGotoLineDialog() {
        TextInputEditText input = new TextInputEditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("Line number");
        new MaterialAlertDialogBuilder(this)
                .setTitle("Go to line")
                .setView(wrapDialogView(input))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String text = input.getText() == null ? "" : input.getText().toString().trim();
                    try {
                        int line = Integer.parseInt(text) - 1;
                        int max = Math.max(binding.editor.getText().getLineCount() - 1, 0);
                        binding.editor.setSelection(Math.max(0, Math.min(line, max)), 0);
                        binding.editor.ensureSelectionVisible();
                        updateCursorPosition();
                    } catch (NumberFormatException ignored) {
                    }
                })
                .setNegativeButton(R.string.common_word_cancel, null)
                .show();
    }

    @NonNull
    private View wrapDialogView(@NonNull EditText input) {
        FrameLayout frame = new FrameLayout(this);
        int padding = (int) (getResources().getDisplayMetrics().density * 20);
        frame.setPadding(padding, padding / 2, padding, 0);
        frame.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return frame;
    }

    private void showFontSizeDialog() {
        TextInputEditText input = new TextInputEditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(String.valueOf(DEFAULT_FONT_SIZE_SP));
        input.setText(String.valueOf(prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE_SP)));
        new MaterialAlertDialogBuilder(this)
                .setTitle("Editor font size (sp)")
                .setView(wrapDialogView(input))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String text = input.getText() == null ? "" : input.getText().toString().trim();
                    try {
                        int size = clampFontSize(Integer.parseInt(text));
                        binding.editor.setTextSize(size);
                        prefs.edit().putInt(KEY_FONT_SIZE, size).apply();
                    } catch (NumberFormatException ignored) {
                    }
                })
                .setNegativeButton(R.string.common_word_cancel, null)
                .show();
    }

    //endregion

    //region Lifecycle

    @Override
    protected void onStop() {
        // Persist open tabs and unsaved content so an OS kill loses at most one keystroke.
        if (prefs != null && generatedPreview == null) {
            mirrorActiveContent();
            persistSessionState();
        }
        super.onStop();
    }

    @Override
    public void onDestroy() {
        try {
            binding.editor.setEditorLanguage(new EmptyLanguage());
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    //endregion

    //region Tabs adapter

    private class EditorTabsAdapter extends RecyclerView.Adapter<EditorTabsAdapter.TabViewHolder> {

        @NonNull
        @Override
        public TabViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemEditorTabBinding binding = ItemEditorTabBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            return new TabViewHolder(binding);
        }

        @Override
        public void onBindViewHolder(@NonNull TabViewHolder holder, int position) {
            SourceEditorSession session = sessions.get(position);
            holder.binding.tabTitle.setText(session.getFileName());
            boolean dirty = session.isDirty();
            holder.binding.tabDirty.setVisibility(dirty ? View.VISIBLE : View.GONE);
            boolean active = position == activeSessionIndex && generatedPreview == null;
            holder.binding.getRoot().setActivated(active);
            holder.binding.tabTitle.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            holder.binding.getRoot().setOnClickListener(v -> openSessionAt(position));
            holder.binding.tabClose.setOnClickListener(v -> closeSession(position));
        }

        @Override
        public int getItemCount() {
            return sessions.size();
        }

        final class TabViewHolder extends RecyclerView.ViewHolder {
            final ItemEditorTabBinding binding;

            TabViewHolder(@NonNull ItemEditorTabBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }

    //endregion
}
