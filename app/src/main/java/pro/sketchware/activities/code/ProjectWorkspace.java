package pro.sketchware.activities.code;

import static pro.sketchware.utility.GsonUtils.getGson;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.besome.sketch.beans.ProjectFileBean;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import a.a.a.ProjectBuilder;
import a.a.a.hC;
import a.a.a.jC;
import a.a.a.lC;
import a.a.a.wq;
import a.a.a.yq;
import pro.sketchware.utility.FileUtil;

/**
 * The real Android project a Sketchware Pro project is built from.
 * <p>
 * Every build of a project starts by wiping and regenerating
 * {@code /storage/emulated/0/.sketchware/mysc/&lt;sc_id&gt;}, producing a genuine Android
 * project layout:
 * <pre>
 * &lt;project name&gt;/
 * ├── app/
 * │   ├── build.gradle
 * │   ├── proguard-rules.pro
 * │   └── src/main/
 * │       ├── AndroidManifest.xml
 * │       ├── java/&lt;package folders&gt;/*.java|*.kt
 * │       ├── res/{layout,values,drawable,mipmap-*,xml,raw}/
 * │       └── assets/
 * ├── build.gradle
 * ├── settings.gradle
 * └── gradle.properties
 * </pre>
 * This class materializes exactly that, using the same generator the build uses
 * ({@code yq}), and then folds the project's user-owned files
 * ({@code .sketchware/data/&lt;sc_id&gt;/files/...}) into it, so the tree shows one complete,
 * real project instead of a set of virtual entries.
 * <p>
 * <b>Why the workspace is not where edits live.</b> A build deletes and regenerates
 * the whole workspace, so a file edited only there would be lost. Every file the user
 * may edit therefore has an <i>override location</i> under
 * {@code .sketchware/data/&lt;sc_id&gt;/}, which the compiler really consumes:
 * <ul>
 *     <li>{@code files/java}, {@code files/broadcast}, {@code files/service} - compiled by
 *     ECJ/kotlinc; a screen's own override also stops the generator from emitting that
 *     screen again,</li>
 *     <li>{@code files/resource} - compiled by aapt2 as imported resources; a screen's
 *     layout override suppresses the generated layout,</li>
 *     <li>{@code files/assets} - linked into the APK,</li>
 *     <li>{@code files/AndroidManifest.xml}, {@code files/gradle/...},
 *     {@code proguard-rules.pro} - honored by the build through the hooks in {@code yq}.</li>
 * </ul>
 * The workspace-to-override mapping is recorded next to the workspace, so saving a file
 * always writes to the location the next build reads.
 */
public final class ProjectWorkspace {

    /**
     * Index file inside the workspace holding the override map and a build stamp.
     * Hidden, so the tree does not show it.
     */
    public static final String INDEX_FILE_NAME = ".sketchcode-index.json";

    private static final Pattern PACKAGE_DECLARATION =
            Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    /** Project metadata files whose modification makes a materialized workspace stale. */
    private static final String[] METADATA_FILES = {
            "file", "logic", "view", "library", "resource", "permission", "project_config"
    };

    private final String scId;
    private final String root;
    private final String dataRoot;
    @NonNull
    private final Map<String, String> overrides;
    /** Generated screen/support Java file names, i.e. names block mode owns. */
    private final Set<String> generatedJavaNames = new LinkedHashSet<>();
    /** Generated layout file names, i.e. layout names block mode owns. */
    private final Set<String> generatedLayoutNames = new LinkedHashSet<>();

    /** Serialized form of the index file. */
    private static final class Index {
        /** workspace-relative path -> override path relative to the project data directory. */
        Map<String, String> overrides = new LinkedHashMap<>();
        long stamp;
    }

    private ProjectWorkspace(@NonNull String scId, @NonNull String root,
                             @NonNull String dataRoot, @NonNull Map<String, String> overrides) {
        this.scId = scId;
        this.root = root;
        this.dataRoot = dataRoot;
        this.overrides = overrides;
        collectGeneratedNames();
    }

    //region Paths

    /** The project's real Android project directory, e.g. {@code .sketchware/mysc/601}. */
    @NonNull
    public static String workspaceRoot(@NonNull String scId) {
        return wq.d(scId);
    }

    /** The project's own data directory, e.g. {@code .sketchware/data/601}. */
    @NonNull
    public static String dataRoot(@NonNull String scId) {
        return new File(FileUtil.getExternalStorageDir(), ".sketchware/data/" + scId).getAbsolutePath();
    }

    @NonNull
    public String getRoot() {
        return root;
    }

    /** The project's name, shown as the tree's root row. */
    @NonNull
    public String getProjectName() {
        try {
            Object name = lC.b(scId).get("my_ws_name");
            if (name instanceof String && !((String) name).isEmpty()) {
                return (String) name;
            }
        } catch (Throwable ignored) {
            // Fall through to the id, which is always available.
        }
        return scId;
    }

    /** Whether {@code path} is inside this workspace. */
    public boolean contains(@NonNull String path) {
        return path.equals(root) || path.startsWith(root + File.separator);
    }

    //endregion

    //region Lifecycle

    /** Loads the workspace if it is already materialized. */
    @NonNull
    public static ProjectWorkspace load(@NonNull String scId) {
        String root = workspaceRoot(scId);
        String dataRoot = dataRoot(scId);
        Index index = readIndex(root);
        return new ProjectWorkspace(scId, root, dataRoot, index.overrides);
    }

    /** Whether the workspace exists and still matches the project's current metadata. */
    public static boolean isPrepared(@NonNull String scId) {
        String root = workspaceRoot(scId);
        if (!new File(root).isDirectory()) {
            return false;
        }
        File indexFile = new File(root, INDEX_FILE_NAME);
        if (!indexFile.isFile()) {
            return false;
        }
        long stamp = readIndex(root).stamp;
        return stamp >= metadataStamp(scId);
    }

    /**
     * Rebuilds the workspace from project metadata, then folds in the project's
     * user-owned files. Runs the same generation path as a build, so what the tree
     * shows is what the next build will compile.
     *
     * @throws Exception if generation fails; the caller decides how to report it
     */
    public static void prepare(@NonNull Context context, @NonNull String scId) throws Exception {
        String root = workspaceRoot(scId);
        // The workspace is a build artifact: regenerated from scratch, exactly like a build.
        FileUtil.deleteFile(root);

        yq generator = new yq(context, scId);
        generator.c(context);
        generator.a();
        try {
            // Base resources every project has (themes, launcher icons, ...).
            generator.a(context, wq.e("600"));
        } catch (Throwable ignored) {
            // The template is optional; generation below still produces a usable project.
        }

        hC projectFiles = jC.b(scId);
        var dataManager = jC.a(scId);
        var libraryManager = jC.c(scId);
        generator.a(libraryManager, projectFiles, dataManager);
        ProjectBuilder builder = new ProjectBuilder(context, generator);
        builder.buildBuiltInLibraryInformation();
        // Writes every generated source: activities, layouts, support classes,
        // AndroidManifest.xml, build.gradle, settings.gradle, gradle.properties.
        generator.b(projectFiles, dataManager, libraryManager, builder.getBuiltInLibraryManager());

        Index index = new Index();
        index.overrides = new LinkedHashMap<>();
        mirrorUserFiles(scId, root, generator, index.overrides);
        index.stamp = metadataStamp(scId);
        writeIndex(root, index);
    }

    //endregion

    //region Override mapping

    /**
     * The file that actually holds {@code workspaceFile}'s content for a build, or
     * {@code null} when the file is generated and has no override yet.
     */
    @Nullable
    public String overrideFor(@NonNull String workspaceFile) {
        String relative = relativeTo(root, workspaceFile);
        if (relative == null) {
            return null;
        }
        return overrideForRelative(relative);
    }

    @Nullable
    private String overrideForRelative(@NonNull String relative) {
        String mapped = overrides.get(relative);
        if (mapped == null) {
            return null;
        }
        return new File(dataRoot, mapped).getAbsolutePath();
    }

    /** Whether {@code workspaceFile} is owned by block mode (generated from a screen). */
    public boolean isGenerated(@NonNull String workspaceFile) {
        String relative = relativeTo(root, workspaceFile);
        if (relative == null) {
            return false;
        }
        if (relative.equals("app/src/main/AndroidManifest.xml")) {
            return true;
        }
        if (relative.startsWith("app/src/main/java/")) {
            return generatedJavaNames.contains(new File(workspaceFile).getName());
        }
        if (relative.startsWith("app/src/main/res/layout/")) {
            return generatedLayoutNames.contains(new File(workspaceFile).getName());
        }
        if (relative.startsWith("app/src/main/res/values/")) {
            String name = new File(workspaceFile).getName();
            return name.equals("strings.xml") || name.equals("colors.xml") || name.equals("styles.xml");
        }
        return false;
    }

    /**
     * Whether a user-owned override exists for {@code workspaceFile}, i.e. block mode
     * no longer regenerates it and the build reads the user's version.
     */
    public boolean isOverridden(@NonNull String workspaceFile) {
        String override = overrideFor(workspaceFile);
        return override != null && FileUtil.isExistFile(override);
    }

    /**
     * The location a file created or edited in the tree must be persisted to, creating
     * the mapping if this file has none yet.
     *
     * @return the absolute override path, or {@code null} if the file is outside the
     * project and therefore not part of the build
     */
    @Nullable
    public String resolveOverrideFor(@NonNull String workspaceFile) {
        String relative = relativeTo(root, workspaceFile);
        if (relative == null) {
            return null;
        }
        String existing = overrides.get(relative);
        if (existing != null) {
            return new File(dataRoot, existing).getAbsolutePath();
        }
        String computed = defaultOverrideRelative(relative);
        if (computed == null) {
            return null;
        }
        overrides.put(relative, computed);
        saveIndex();
        return new File(dataRoot, computed).getAbsolutePath();
    }

    /**
     * Forgets the override of a deleted file so the tree does not keep a mapping to
     * something that no longer exists.
     */
    public void forgetOverride(@NonNull String workspaceFileOrSubtree) {
        String relative = relativeTo(root, workspaceFileOrSubtree);
        if (relative == null) {
            return;
        }
        String prefix = relative + "/";
        boolean changed = overrides.keySet().removeIf(key -> key.equals(relative) || key.startsWith(prefix));
        if (changed) {
            saveIndex();
        }
    }

    /**
     * Where an edit of {@code relative} belongs inside the project data directory,
     * following the directories the build actually reads.
     * <p>
     * A file block mode generates needs its project-owned copy at the <i>flat</i> path
     * ({@code files/java/MainActivity.java}, {@code files/resource/layout/activity_main.xml}):
     * that exact name is what tells the generator to skip its own version. Any other
     * file keeps its relative path, because the compilers read those directories
     * recursively.
     */
    @Nullable
    private String defaultOverrideRelative(@NonNull String relative) {
        if (relative.startsWith("app/src/main/java/")) {
            String withinJava = relative.substring("app/src/main/java/".length());
            String name = withinJava.substring(withinJava.lastIndexOf('/') + 1);
            if (generatedJavaNames.contains(name)) {
                return "files/java/" + name;
            }
            return "files/java/" + withinJava;
        }
        if (relative.startsWith("app/src/main/res/")) {
            String withinRes = relative.substring("app/src/main/res/".length());
            if (withinRes.startsWith("layout/")) {
                String name = withinRes.substring("layout/".length());
                if (generatedLayoutNames.contains(name)) {
                    return "files/resource/layout/" + name;
                }
            }
            return "files/resource/" + withinRes;
        }
        if (relative.startsWith("app/src/main/assets/")) {
            return "files/assets/" + relative.substring("app/src/main/assets/".length());
        }
        if (relative.startsWith("app/src/main/jniLibs/")) {
            return "files/native_libs/" + relative.substring("app/src/main/jniLibs/".length());
        }
        if (relative.equals("app/src/main/AndroidManifest.xml")) {
            return "files/AndroidManifest.xml";
        }
        if (relative.equals("app/proguard-rules.pro") || relative.equals("proguard-rules.pro")) {
            return "proguard-rules.pro";
        }
        // Build files: build.gradle, settings.gradle, gradle.properties, app/build.gradle.
        if (relative.equals("build.gradle") || relative.equals("settings.gradle")
                || relative.equals("gradle.properties") || relative.equals("app/build.gradle")) {
            return "files/gradle/" + relative;
        }
        return null;
    }

    //endregion

    //region Materialization

    /**
     * Copies every user-owned file of the project into the matching place in the
     * workspace and records where each one came from.
     */
    private static void mirrorUserFiles(@NonNull String scId, @NonNull String root,
                                        @NonNull yq generator,
                                        @NonNull Map<String, String> out) {
        String pkgFolders = packageFolders(generator.packageName);
        String javaRoot = new File(root, "app/src/main/java").getAbsolutePath();

        File userFiles = new File(dataRoot(scId), "files");
        // Java roots the compiler is given as source paths.
        mirrorJava(new File(userFiles, "java"), root, javaRoot, pkgFolders, "files/java", out);
        mirrorJava(new File(userFiles, "broadcast"), root, javaRoot, pkgFolders, "files/broadcast", out);
        mirrorJava(new File(userFiles, "service"), root, javaRoot, pkgFolders, "files/service", out);

        mirrorTree(new File(userFiles, "resource"), root, "app/src/main/res", "files/resource", out);
        mirrorTree(new File(userFiles, "assets"), root, "app/src/main/assets", "files/assets", out);
        mirrorTree(new File(userFiles, "native_libs"), root, "app/src/main/jniLibs", "files/native_libs", out);

        // Manifest: generated, but a user override wins once it exists.
        File manifestOverride = new File(userFiles, "AndroidManifest.xml");
        if (manifestOverride.isFile()) {
            writeFile(manifestOverride, new File(root, "app/src/main/AndroidManifest.xml"));
            out.put("app/src/main/AndroidManifest.xml", "files/AndroidManifest.xml");
        }

        // Gradle files: generated by yq#generateGradleFiles, overridable by files/gradle.
        for (String name : new String[]{"build.gradle", "settings.gradle", "gradle.properties",
                "app/build.gradle"}) {
            File override = new File(new File(userFiles, "gradle"), name);
            if (override.isFile()) {
                writeFile(override, new File(root, name));
                out.put(name, "files/gradle/" + name);
            }
        }

        // Proguard rules: the project's authoritative file is data/<sc_id>/proguard-rules.pro.
        File proguard = new File(dataRoot(scId), "proguard-rules.pro");
        if (proguard.isFile()) {
            writeFile(proguard, new File(root, "app/proguard-rules.pro"));
            out.put("app/proguard-rules.pro", "proguard-rules.pro");
        }
    }

    /**
     * Mirrors {@code .java}/{@code .kt} files into their real package directory. A flat
     * file's own {@code package} declaration decides where it belongs, so a file the
     * user dropped into {@code files/java} shows up where it is compiled from.
     */
    private static void mirrorJava(@Nullable File source, @NonNull String root,
                                   @NonNull String javaRoot, @NonNull String pkgFolders,
                                   @NonNull String overridePrefix,
                                   @NonNull Map<String, String> out) {
        if (source == null || !source.isDirectory()) {
            return;
        }
        String base = source.getAbsolutePath();
        for (File file : filesUnder(source)) {
            String name = file.getName();
            if (!name.endsWith(".java") && !name.endsWith(".kt")) {
                continue;
            }
            String relative = relativeTo(base, file.getAbsolutePath());
            if (relative == null) {
                continue;
            }
            File destination;
            if (relative.contains("/")) {
                // Already in a package structure: keep it.
                destination = new File(javaRoot, relative);
            } else {
                String folders = declaredPackage(file);
                destination = new File(new File(javaRoot, folders == null ? pkgFolders : folders), name);
            }
            writeFile(file, destination);
            String workspaceRelative = relativeTo(root, destination.getAbsolutePath());
            if (workspaceRelative != null) {
                out.put(workspaceRelative, overridePrefix + "/" + relative);
            }
        }
    }

    /** Mirrors a whole directory into the workspace, recording every mapping. */
    private static void mirrorTree(@Nullable File source, @NonNull String root,
                                   @NonNull String destinationRelative,
                                   @NonNull String overridePrefix, @NonNull Map<String, String> out) {
        if (source == null || !source.isDirectory()) {
            return;
        }
        File destination = new File(root, destinationRelative);
        String base = source.getAbsolutePath();
        for (File file : filesUnder(source)) {
            String relative = relativeTo(base, file.getAbsolutePath());
            if (relative == null) {
                continue;
            }
            writeFile(file, new File(destination, relative));
            out.put(destinationRelative + "/" + relative, overridePrefix + "/" + relative);
        }
    }

    /**
     * Records that the user just saved one of this project's files, so the workspace is
     * not treated as stale (and therefore regenerated) on the next visit.
     */
    public void noteSaved() {
        saveIndex();
    }

    /**
     * Records every mapping in the workspace's index file. Called after a save so a new
     * file's override survives the next materialization.
     */
    private void saveIndex() {
        Index index = readIndex(root);
        index.stamp = metadataStamp(scId);
        index.overrides = new LinkedHashMap<>(overrides);
        writeIndex(root, index);
    }

    //endregion

    //region Metadata

    /** File names block mode generates for this project's screens and support classes. */
    private void collectGeneratedNames() {
        try {
            hC projectFiles = jC.b(scId);
            addScreenNames(projectFiles.b(), generatedJavaNames, generatedLayoutNames);
            addScreenNames(projectFiles.c(), generatedJavaNames, generatedLayoutNames);
        } catch (Throwable ignored) {
            // Broken metadata: the tree still lists real files, just without ownership badges.
        }
        // Support classes the generator emits unconditionally (or on demand).
        for (String name : new String[]{"SketchwareUtil.java", "FileUtil.java", "RequestNetwork.java",
                "RequestNetworkController.java", "BluetoothConnect.java", "BluetoothController.java",
                "GoogleMapController.java", "DebugActivity.java", "SketchLogger.java",
                "SketchApplication.java"}) {
            generatedJavaNames.add(name);
        }
    }

    private static void addScreenNames(@Nullable List<ProjectFileBean> beans,
                                       @NonNull Set<String> javaNames,
                                       @NonNull Set<String> layoutNames) {
        if (beans == null) {
            return;
        }
        for (ProjectFileBean bean : beans) {
            if (bean == null) {
                continue;
            }
            String javaName = bean.getJavaName();
            if (javaName != null && !javaName.isEmpty()) {
                javaNames.add(javaName.endsWith(".java") || javaName.endsWith(".kt")
                        ? javaName : javaName + ".java");
            }
            String xmlName = bean.getXmlName();
            if (xmlName != null && !xmlName.isEmpty()) {
                layoutNames.add(xmlName.endsWith(".xml") ? xmlName : xmlName + ".xml");
            }
        }
    }

    /** Newest modification time among the project's metadata files. */
    private static long metadataStamp(@NonNull String scId) {
        long stamp = 0L;
        File dataDir = new File(dataRoot(scId));
        for (String name : METADATA_FILES) {
            stamp = Math.max(stamp, new File(dataDir, name).lastModified());
        }
        // Adding or removing a user-owned file also changes what the workspace should show.
        for (String name : new String[]{"java", "resource", "assets", "broadcast", "service"}) {
            stamp = Math.max(stamp, new File(new File(dataDir, "files"), name).lastModified());
        }
        return stamp;
    }

    //endregion

    //region Index file

    @NonNull
    private static Index readIndex(@NonNull String root) {
        Index index = new Index();
        try {
            String json = FileUtil.readFileIfExist(new File(root, INDEX_FILE_NAME).getAbsolutePath());
            if (!json.isEmpty()) {
                Type type = TypeToken.get(Index.class).getType();
                Index loaded = getGson().fromJson(json, type);
                if (loaded != null && loaded.overrides != null) {
                    index.overrides = loaded.overrides;
                    index.stamp = loaded.stamp;
                }
            }
        } catch (Throwable ignored) {
            // A damaged index must never break the tree; the default mapping still works.
        }
        return index;
    }

    private static void writeIndex(@NonNull String root, @NonNull Index index) {
        try {
            FileUtil.makeDir(root);
            FileUtil.writeFile(new File(root, INDEX_FILE_NAME).getAbsolutePath(),
                    getGson().toJson(index));
        } catch (Throwable ignored) {
            // The index is a cache: losing it only costs one regeneration.
        }
    }

    //endregion

    //region Small helpers

    @NonNull
    private static String packageFolders(@Nullable String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return "";
        }
        return packageName.replace('.', File.separatorChar);
    }

    /** The package a Java/Kotlin file declares, as a path, or {@code null} if it declares none. */
    @Nullable
    private static String declaredPackage(@NonNull File file) {
        try {
            String head = FileUtil.readFile(file.getAbsolutePath());
            if (head == null) {
                return null;
            }
            if (head.length() > 8000) {
                head = head.substring(0, 8000);
            }
            Matcher matcher = PACKAGE_DECLARATION.matcher(head);
            if (matcher.find() && matcher.group(1) != null) {
                return packageFolders(matcher.group(1));
            }
        } catch (Throwable ignored) {
            // Unreadable file: fall back to the project package.
        }
        return null;
    }

    /** Every regular file below {@code directory}, depth first, in listing order. */
    @NonNull
    private static List<File> filesUnder(@Nullable File directory) {
        List<File> files = new ArrayList<>();
        collectFiles(directory, files, 0);
        return files;
    }

    private static void collectFiles(@Nullable File directory, @NonNull List<File> out, int depth) {
        if (directory == null || depth > 32) {
            return;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        List<File> sorted = new ArrayList<>(List.of(children));
        sorted.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File child : sorted) {
            if (child.isDirectory()) {
                collectFiles(child, out, depth + 1);
            } else if (child.isFile()) {
                out.add(child);
            }
        }
    }

    /** {@code path} relative to {@code base}, using {@code /}, or {@code null} if outside. */
    @Nullable
    private static String relativeTo(@NonNull String base, @NonNull String path) {
        if (path.equals(base)) {
            return "";
        }
        String prefix = base.endsWith(File.separator) ? base : base + File.separator;
        if (!path.startsWith(prefix)) {
            return null;
        }
        return path.substring(prefix.length()).replace(File.separatorChar, '/');
    }

    private static void writeFile(@NonNull File source, @NonNull File destination) {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists()) {
            FileUtil.makeDir(parent.getAbsolutePath());
        }
        FileUtil.copyFile(source.getAbsolutePath(), destination.getAbsolutePath());
    }

    //endregion
}
