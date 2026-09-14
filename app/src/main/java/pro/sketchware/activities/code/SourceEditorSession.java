package pro.sketchware.activities.code;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Owns the state of one file opened in the code editor:
 * the last-saved content, the on-disk file it maps to and its
 * last-known modification stamp for external-change detection.
 * <p>
 * The editor's live text is only mirrored into this session at
 * well-defined points (tab switch, save, stop) to avoid copying
 * large documents on every keystroke.
 * <p>
 * Saving is crash-safe: content is first written to a temp file in the
 * same directory and then renamed over the target, so a crash mid-write
 * can never leave a truncated project source file behind.
 */
public final class SourceEditorSession {

    @NonNull
    private final String filePath;
    @NonNull
    private String savedContent;
    /** Content the editor currently shows (mirrored; not updated per keystroke). */
    @NonNull
    private String currentContent;
    /** True when the editor content changed since the last save/mirror. */
    private boolean modified;
    private long savedLastModified;
    private boolean externalChangeNotified;

    private SourceEditorSession(@NonNull String filePath, @NonNull String savedContent, long lastModified) {
        this.filePath = filePath;
        this.savedContent = savedContent;
        this.currentContent = savedContent;
        this.savedLastModified = lastModified;
    }

    /**
     * Reads the file and creates a session. Missing files are tolerated and treated
     * as empty documents (the editor can still be used to create content on save).
     */
    @NonNull
    public static SourceEditorSession open(@NonNull String filePath) {
        String content = "";
        long lastModified = 0L;
        try {
            File file = new File(filePath);
            if (file.exists() && file.isFile()) {
                lastModified = file.lastModified();
                content = readFully(file);
            }
        } catch (Exception e) {
            content = "";
        }
        return new SourceEditorSession(filePath, content, lastModified);
    }

    @NonNull
    public String getFilePath() {
        return filePath;
    }

    @NonNull
    public String getFileName() {
        String path = filePath;
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    @NonNull
    public String getSavedContent() {
        return savedContent;
    }

    @NonNull
    public String getCurrentContent() {
        return currentContent;
    }

    /** Mirrors the editor's live text into this session (copy of the full content). */
    public void setCurrentContent(@NonNull String content) {
        currentContent = content;
    }

    public void markModified() {
        modified = true;
    }

    public boolean isModified() {
        return modified;
    }

    public boolean isDirty() {
        return modified || !savedContent.equals(currentContent);
    }

    /**
     * @return true if the file changed on disk after it was loaded (and no save happened since).
     */
    public boolean hasExternalModification() {
        File file = new File(filePath);
        if (!file.exists()) {
            return false;
        }
        return savedLastModified != 0L && file.lastModified() != savedLastModified;
    }

    public void markExternalChangeNotified() {
        externalChangeNotified = true;
    }

    public boolean wasExternalChangeNotified() {
        return externalChangeNotified;
    }

    /**
     * Atomically writes {@code content} to the target file:
     * writes to "&lt;name&gt;.swtmp" first, then renames over the original.
     *
     * @return true when the file was written and the rename succeeded
     */
    public boolean save(@NonNull String content) {
        File target = new File(filePath);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return false;
        }
        File tmp = new File(parent != null ? parent : new File("."), target.getName() + ".swtmp");
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
                out.getFD().sync();
            }
            if (target.exists() && !target.delete()) {
                boolean written = writeDirect(target, content);
                deleteQuietly(tmp);
                return finishSave(written, content);
            }
            if (!tmp.renameTo(target)) {
                boolean written = writeDirect(target, content);
                deleteQuietly(tmp);
                return finishSave(written, content);
            }
        } catch (IOException e) {
            deleteQuietly(tmp);
            return false;
        }
        return finishSave(true, content);
    }

    private boolean finishSave(boolean success, @NonNull String savedText) {
        if (!success) {
            return false;
        }
        savedContent = savedText;
        currentContent = savedText;
        modified = false;
        File file = new File(filePath);
        savedLastModified = file.exists() ? file.lastModified() : 0L;
        externalChangeNotified = false;
        return true;
    }

    /**
     * Reloads the saved content from disk (used after the user accepts an external change).
     *
     * @return the newly loaded content, or null when reading failed
     */
    @Nullable
    public String reloadFromDisk() {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                return null;
            }
            String content = readFully(file);
            savedContent = content;
            currentContent = content;
            modified = false;
            savedLastModified = file.lastModified();
            externalChangeNotified = false;
            return content;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean writeDirect(@NonNull File target, @NonNull String content) {
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(bytes);
                out.getFD().sync();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteQuietly(@NonNull File file) {
        if (file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    @NonNull
    private static String readFully(@NonNull File file) throws IOException {
        // Read through a decoding Reader so multi-byte UTF-8 characters that span
        // read-buffer boundaries are never corrupted.
        StringBuilder builder = new StringBuilder((int) Math.min(file.length() + 16L, Integer.MAX_VALUE - 8));
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) > 0) {
                builder.append(buffer, 0, read);
            }
        }
        return builder.toString();
    }
}
