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

import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.langs.java.JavaLanguage;
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme;
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
import pro.sketchware.utility.SketchwareUtil;
import pro.sketchware.utility.ThemeUtils;

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
 */
public class ProjectCodeEditorActivity extends BaseAppCompatActivity {

    public static final String EXTRA_SC_ID = "sc_id";
    /** Optional: open this absolute path directly on launch. */
    public static final String EXTRA_OPEN_PATH = "open_path";

    private static final String PREFS_NAME = "project_code_editor";
    private static final String KEY_FONT_SIZE = "font_size";
    private static final String KEY_WORD_WRAP = "word_wrap";
    private static final String KEY_AUTO_COMPLETE = "auto_complete";
    private static final String KEY_SYMBOL_PAIRS = "symbol_pairs";
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

    private final OnBackPressedCallback backPressedCallback = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
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

        setSupportActionBar(binding.toolbar);
        binding.toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        getOnBackPressedDispatcher().addCallback(this, backPressedCallback);

        tabsAdapter = new EditorTabsAdapter();
        binding.editorTabs.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        binding.editorTabs.setAdapter(tabsAdapter);

        configureEditor();
        binding.editor.getText().addContentListener(contentListener);
        restoreSessionState();

        String openPath = getIntent().getStringExtra(EXTRA_OPEN_PATH);
        if (openPath != null && !openPath.isEmpty()) {
            int existing = indexOfSession(openPath);
            if (existing >= 0) {
                openSessionAt(existing);
            } else {
                openFile(openPath);
            }
        }
        updateEmptyState();
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
        editor.getComponent(EditorAutoCompletion.class).setEnabled(prefs.getBoolean(KEY_AUTO_COMPLETE, true));
        editor.setPinLineNumber(true);
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
        if (applyingProgrammaticText
                || activeSessionIndex < 0
                || activeSessionIndex >= sessions.size()) {
            return;
        }
        if (!sessions.get(activeSessionIndex).isModified()) {
            sessions.get(activeSessionIndex).markModified();
            tabsAdapter.notifyItemChanged(activeSessionIndex);
            updateTitle();
        }
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
        if (activeSessionIndex != index) {
            mirrorActiveContent();
        }
        activeSessionIndex = index;
        SourceEditorSession session = sessions.get(index);
        applyingProgrammaticText = true;
        binding.editor.setText(session.getCurrentContent());
        binding.editor.setEditorLanguage(languageFor(session.getFileName()));
        applyColorSchemeFor(session.getFileName());
        applyingProgrammaticText = false;
        restoreCursorFor(session.getFilePath());
        updateTitle();
        tabsAdapter.notifyDataSetChanged();
        updateEmptyState();
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
                    .setNegativeButton("Reload", (dialog, which) -> {
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
        boolean empty = sessions.isEmpty();
        binding.editor.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.editorTabs.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.noFilesLayout.setVisibility(empty ? View.VISIBLE : View.GONE);
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
        if (activeSessionIndex >= 0) {
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
        MenuItem preview = menu.findItem(R.id.action_layout_preview);
        if (preview != null) {
            preview.setVisible(isLayoutFile());
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
            if (activeSessionIndex >= 0 && !saveSession(activeSessionIndex)) {
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
        mirrorActiveContent();
        persistSessionState();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
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
            boolean active = position == activeSessionIndex;
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
