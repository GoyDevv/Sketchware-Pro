package pro.sketchware.activities.code;

import static pro.sketchware.utility.GsonUtils.getGson;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import pro.sketchware.utility.FileUtil;
import pro.sketchware.utility.FilePathUtil;

/**
 * Tracks which Sketchware-generated project files the user has "customized" in Code Mode.
 * <p>
 * A customized generated file (activity .java, layout .xml, AndroidManifest.xml, styles/colors/strings.xml)
 * is copied once from its canonical generation location into the user-owned override directory
 * (".sketchware/data/&lt;sc_id&gt;/files/..."), which the build pipeline treats as authoritative.
 * The catalog remembers that mapping so the explorer can:
 * <ul>
 *     <li>show the correct title per entry ("MainActivity.java (customized)"),</li>
 *     <li>offer "Reset to generated" to return to block-generated output,</li>
 *     <li>keep the override alive across builds, because the build skips generating
 *     any file already present in the user-owned directories.</li>
 * </ul>
 * The catalog is a single JSON file, written atomically (temp file + rename) so a crash
 * can never corrupt project metadata. Unknown/corrupt content falls back to an empty catalog.
 */
public final class ProjectFileCatalog {

    private static final String CATALOG_DIR = "code_editor";

    /** Descriptor of one user-customized generated file. */
    public static final class Entry {
        /** sc-relative path of the user-owned file, e.g. "files/java/MainActivity.java". */
        public String overridePath;
        /** Where the generated version lives (for "reset to generated"), sc-relative. */
        public String generatedPath;
        /** Display title, e.g. "MainActivity.java". */
        public String title;
    }

    private final File catalogFile;
    private final List<Entry> entries;

    private ProjectFileCatalog(File catalogFile, List<Entry> entries) {
        this.catalogFile = catalogFile;
        this.entries = entries;
    }

    @NonNull
    public static ProjectFileCatalog load(@NonNull String scId) {
        // getPathJava -> .sketchware/data/<sc_id>/files/java; climb to .sketchware/data/<sc_id>.
        File filesJava = new File(new FilePathUtil().getPathJava(scId));
        File dataDir = filesJava.getParentFile() != null ? filesJava.getParentFile().getParentFile() : null;
        File store = dataDir != null
                ? new File(dataDir, CATALOG_DIR + File.separator + "customized.json")
                : new File(CATALOG_DIR, "customized.json");
        List<Entry> entries = new ArrayList<>();
        if (FileUtil.isExistFile(store.getAbsolutePath())) {
            try {
                String json = FileUtil.readFileIfExist(store.getAbsolutePath());
                if (!json.isEmpty()) {
                    Type type = TypeToken.getParameterized(List.class, Entry.class).getType();
                    List<Entry> loaded = getGson().fromJson(json, type);
                    if (loaded != null) {
                        entries.addAll(loaded);
                    }
                }
            } catch (Exception e) {
                // Corrupt catalog must never take the explorer down; start empty.
                entries.clear();
            }
        }
        return new ProjectFileCatalog(store, entries);
    }

    /** Marks a generated file as customized. Does nothing if already tracked. */
    public void markCustomized(@NonNull String overridePath, @NonNull String generatedPath, @NonNull String title) {
        if (findByOverride(overridePath) != null) {
            return;
        }
        Entry entry = new Entry();
        entry.overridePath = overridePath;
        entry.generatedPath = generatedPath;
        entry.title = title;
        entries.add(entry);
        save();
    }

    /** Removes the customization record; the override file itself is untouched. */
    public void removeByOverride(@NonNull String overridePath) {
        Entry found = findByOverride(overridePath);
        if (found != null) {
            entries.remove(found);
            save();
        }
    }

    /** Removes every record whose override file no longer exists (e.g. file deleted externally). */
    public void pruneMissing() {
        boolean changed = entries.removeIf(entry ->
                entry == null || entry.overridePath == null || !FileUtil.isExistFile(absolute(entry.overridePath)));
        if (changed) {
            save();
        }
    }

    @Nullable
    public Entry findByOverride(@NonNull String overridePath) {
        for (Entry entry : entries) {
            if (entry != null && overridePath.equals(entry.overridePath)) {
                return entry;
            }
        }
        return null;
    }

    @NonNull
    public List<Entry> getEntries() {
        return new ArrayList<>(entries);
    }

    @NonNull
    private String absolute(@NonNull String scRelativePath) {
        return new File(FileUtil.getExternalStorageDir(), ".sketchware/data/" + scRelativePath).getAbsolutePath();
    }

    private void save() {
        File parent = catalogFile.getParentFile();
        if (parent != null && !parent.exists()) {
            FileUtil.makeDir(parent.getAbsolutePath());
        }
        String tmp = catalogFile.getAbsolutePath() + ".tmp";
        FileUtil.writeFile(tmp, getGson().toJson(entries));
        File tmpFile = new File(tmp);
        File target = new File(catalogFile.getAbsolutePath());
        if (target.exists() && !target.delete()) {
            // Could not replace the catalog; keep the old one rather than losing data.
            return;
        }
        if (!tmpFile.renameTo(target)) {
            // Rename can fail across some mounts; fall back to a plain write.
            FileUtil.writeFile(catalogFile.getAbsolutePath(), getGson().toJson(entries));
            FileUtil.deleteFile(tmp);
        }
    }
}
