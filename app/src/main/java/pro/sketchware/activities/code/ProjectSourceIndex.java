package pro.sketchware.activities.code;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.besome.sketch.beans.ProjectFileBean;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.besome.sketch.beans.SrcCodeBean;

import a.a.a.ProjectBuilder;
import a.a.a.hC;
import a.a.a.jC;
import a.a.a.yq;
import mod.hey.studios.project.ProjectSettings;
import pro.sketchware.utility.FilePathUtil;

/**
 * The set of source files a Sketchware Pro project is built from.
 * <p>
 * Two things end up in every build of a project:
 * <ol>
 *     <li>files the user owns, stored under
 *     {@code .sketchware/data/<sc_id>/files} - {@code java/}, {@code resource/},
 *     {@code assets/}, {@code broadcast/}, {@code service/}, ... plus</li>
 *     <li>files Sketchware generates from block metadata: one class and one layout
 *     per screen, the support classes, and {@code strings}/{@code colors}/{@code styles}.xml.</li>
 * </ol>
 * This class knows the second group, including exactly which directory each
 * generated file belongs to - so a generated file can be shown in the tree at the
 * place its user-owned copy would live, and {@code Customize} writes it somewhere
 * the build really reads.
 * <p>
 * {@link #create(String)} answers instantly from project metadata (screens, custom
 * views and the always-generated support classes). {@link #enrichFromGenerator}
 * then asks the real code generator for its complete list, which adds the
 * conditional files (HTTP3, Bluetooth, Maps, view binding) and stays correct if the
 * generator ever grows more outputs.
 */
public final class ProjectSourceIndex {

    /** Kinds, used by the explorer to pick icons, badges and actions. */
    public static final String KIND_ACTIVITY = "activity";
    public static final String KIND_CUSTOM_VIEW = "custom_view";
    public static final String KIND_SUPPORT = "support";
    public static final String KIND_VIEW_BINDING = "view_binding";
    public static final String KIND_VALUES = "values";

    /** One generated file: its name and the directory its user-owned copy belongs to. */
    public static final class Entry {
        public final String name;
        /** Absolute directory under {@code files/} that the build reads this from. */
        public final String directory;
        public final String kind;

        Entry(@NonNull String name, @NonNull String directory, @NonNull String kind) {
            this.name = name;
            this.directory = directory;
            this.kind = kind;
        }
    }

    private final String javaRoot;
    private final String layoutRoot;
    private final String valuesRoot;
    private final String xmlRoot;
    /** Generated files grouped by the directory they belong to, in insertion order. */
    private final Map<String, List<Entry>> byDirectory = new LinkedHashMap<>();
    /** Deduplication key: {@code directory + '\u0000' + name}. */
    private final Set<String> known = new LinkedHashSet<>();

    private ProjectSourceIndex(@NonNull String javaRoot, @NonNull String resourceRoot) {
        this.javaRoot = javaRoot;
        this.layoutRoot = new File(resourceRoot, "layout").getAbsolutePath();
        this.valuesRoot = new File(resourceRoot, "values").getAbsolutePath();
        this.xmlRoot = new File(resourceRoot, "xml").getAbsolutePath();
    }

    /**
     * Builds the index from project metadata. Does no source generation, so it is
     * cheap enough to run while the screen is being created.
     */
    @NonNull
    public static ProjectSourceIndex create(@NonNull String scId) {
        FilePathUtil paths = new FilePathUtil();
        ProjectSourceIndex index = new ProjectSourceIndex(paths.getPathJava(scId),
                paths.getPathResource(scId));
        index.addFromMetadata(scId);
        return index;
    }

    /** Generated files that belong in {@code directory}, in a stable order. */
    @NonNull
    public List<Entry> inDirectory(@NonNull String directory) {
        List<Entry> entries = byDirectory.get(directory);
        return entries == null ? new ArrayList<>() : new ArrayList<>(entries);
    }

    /** The directory generated {@code values} resources are read from. */
    @NonNull
    public String getValuesRoot() {
        return valuesRoot;
    }

    //region Metadata-derived entries

    private void addFromMetadata(@NonNull String scId) {
        hC projectFiles = null;
        try {
            projectFiles = jC.b(scId);
        } catch (Throwable ignored) {
            // Broken metadata: the always-generated entries below are still correct.
        }
        if (projectFiles != null) {
            try {
                addScreens(projectFiles.b(), KIND_ACTIVITY);
            } catch (Throwable ignored) {
                // Ignore and keep whatever else was collected.
            }
            try {
                addScreens(projectFiles.c(), KIND_CUSTOM_VIEW);
            } catch (Throwable ignored) {
                // Ignore and keep whatever else was collected.
            }
        }

        // Support classes yq generates unconditionally, plus the debug helper and the
        // application class (writing either one into files/java replaces the generated one).
        add(javaRoot, "SketchwareUtil.java", KIND_SUPPORT);
        add(javaRoot, "FileUtil.java", KIND_SUPPORT);
        add(javaRoot, "DebugActivity.java", KIND_SUPPORT);
        add(javaRoot, applicationClassName(scId) + ".java", KIND_SUPPORT);

        // Generated resources. The build compiles files/resource as an aapt2 overlay,
        // so a copy here wins over the generated one.
        add(valuesRoot, "strings.xml", KIND_VALUES);
        add(valuesRoot, "colors.xml", KIND_VALUES);
        add(valuesRoot, "styles.xml", KIND_VALUES);
    }

    private void addScreens(@Nullable List<ProjectFileBean> beans, @NonNull String kind) {
        if (beans == null) {
            return;
        }
        for (ProjectFileBean bean : beans) {
            if (bean == null) {
                continue;
            }
            String javaName = bean.getJavaName();
            if (javaName != null && !javaName.isEmpty()) {
                add(javaRoot, withExtension(javaName, ".java"), kind);
            }
            String xmlName = bean.getXmlName();
            if (xmlName != null && !xmlName.isEmpty()) {
                add(layoutRoot, withExtension(xmlName, ".xml"), KIND_ACTIVITY);
            }
        }
    }

    @NonNull
    private static String applicationClassName(@NonNull String scId) {
        try {
            String configured = new ProjectSettings(scId).getValue(
                    ProjectSettings.SETTING_APPLICATION_CLASS, "SketchApplication");
            if (configured != null && !configured.isEmpty()) {
                String simple = configured.startsWith(".") ? configured.substring(1) : configured;
                int dot = simple.lastIndexOf('.');
                if (dot >= 0) {
                    simple = simple.substring(dot + 1);
                }
                if (!simple.isEmpty()) {
                    return simple;
                }
            }
        } catch (Throwable ignored) {
            // Fall through to the default application class name.
        }
        return "SketchApplication";
    }

    //endregion

    //region Generator-derived entries

    /**
     * Asks Sketchware's own generator for every source file it would produce for this
     * project and merges anything new in. Runs the same pipeline as the app's
     * "View all sources" screen, so the tree matches it exactly.
     *
     * @return how many entries were added, so callers can skip a re-render when nothing changed
     */
    public int enrichFromGenerator(@NonNull Context appContext, @NonNull String scId) {
        List<SrcCodeBean> beans;
        try {
            yq generator = new yq(appContext, scId);
            var projectFiles = jC.b(scId);
            var dataManager = jC.a(scId);
            var libraryManager = jC.c(scId);
            generator.a(libraryManager, projectFiles, dataManager, yq.ExportType.SOURCE_CODE_VIEWING);
            ProjectBuilder builder = new ProjectBuilder(appContext, generator);
            builder.buildBuiltInLibraryInformation();
            beans = generator.a(projectFiles, dataManager, builder.getBuiltInLibraryManager());
        } catch (Throwable t) {
            // Generation can legitimately fail (odd metadata, missing assets). The
            // metadata-derived entries already shown are still correct.
            return 0;
        }
        if (beans == null) {
            return 0;
        }
        int before = known.size();
        for (SrcCodeBean bean : beans) {
            if (bean == null || bean.srcFileName == null || bean.srcFileName.isEmpty()) {
                continue;
            }
            String name = bean.srcFileName;
            String directory = directoryFor(name);
            if (directory == null) {
                // AndroidManifest.xml is generated from metadata and has no override
                // location; the explorer shows it as its own entry.
                continue;
            }
            add(directory, name, kindFor(name));
        }
        return known.size() - before;
    }

    /**
     * Where the build reads a generated file from, mirroring how the generator
     * itself chooses a destination (see {@code yq#c(String, String, ...)}): values
     * XML goes to {@code resource/values}, {@code provider_paths.xml} to
     * {@code resource/xml}, every other XML to {@code resource/layout}, and Java to
     * {@code files/java}.
     */
    @Nullable
    private String directoryFor(@NonNull String name) {
        if (name.equals("strings.xml") || name.equals("colors.xml") || name.equals("styles.xml")) {
            return valuesRoot;
        }
        if (name.equals("provider_paths.xml")) {
            return xmlRoot;
        }
        if (name.endsWith(".java")) {
            return javaRoot;
        }
        if (name.endsWith(".xml")) {
            return layoutRoot;
        }
        return null;
    }

    @NonNull
    private static String kindFor(@NonNull String name) {
        if (name.endsWith(".java")) {
            return name.endsWith("Binding.java") ? KIND_VIEW_BINDING : KIND_SUPPORT;
        }
        return KIND_ACTIVITY;
    }

    //endregion

    //region Helpers

    private void add(@NonNull String directory, @NonNull String name, @NonNull String kind) {
        String key = directory + '\u0000' + name;
        if (!known.add(key)) {
            return;
        }
        byDirectory.computeIfAbsent(directory, ignored -> new ArrayList<>())
                .add(new Entry(name, directory, kind));
    }

    @NonNull
    private static String withExtension(@NonNull String name, @NonNull String extension) {
        return name.endsWith(extension) ? name : name + extension;
    }

    /**
     * Directories the tree needs on disk to show its generated files inline - only
     * the ones that actually hold an entry, so an unused folder is never created.
     */
    @NonNull
    public List<String> requiredDirectories() {
        Set<String> directories = new LinkedHashSet<>();
        directories.add(javaRoot);
        directories.add(layoutRoot);
        directories.add(valuesRoot);
        directories.addAll(byDirectory.keySet());
        return new ArrayList<>(directories);
    }

    //endregion
}
