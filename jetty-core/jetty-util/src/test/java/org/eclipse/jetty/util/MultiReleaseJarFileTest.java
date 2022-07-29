//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class MultiReleaseJarFileTest
{
    public WorkDir workDir;
    private static FileSystem metaInfVersionTestFileSystem;

    @AfterAll
    public static void closeFileSystems()
    {
        IO.close(metaInfVersionTestFileSystem);
    }

    private void createExampleJar(Path outputJar) throws IOException
    {
        Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        URI uri = URI.create("jar:" + outputJar.toUri().toASCIIString());
        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env))
        {
            Path root = zipfs.getPath("/");

            writeString(root.resolve("META-INF/MANIFEST.MF"), """
                Manifest-Version: 1.0
                Multi-Release: true
                Created-By: MultiReleaseJarFileTest
                """);

            writeString(root.resolve("META-INF/versions/10/org/example/In10Only.class"), "In10Only (versions/10)");
            writeString(root.resolve("META-INF/versions/10/org/example/InBoth.class"), "InBoth (versions/10)");

            writeString(root.resolve("META-INF/versions/9/org/example/Nowhere$NoOuter.class"), "Nowhere$NoOuter (versions/9)");
            writeString(root.resolve("META-INF/versions/9/org/example/InBoth$Inner9.class"), "InBoth$Inner9 (versions/9)");
            writeString(root.resolve("META-INF/versions/9/org/example/InBoth$InnerBoth.class"), "InBoth$Inner9 (versions/9)");
            writeString(root.resolve("META-INF/versions/9/org/example/OnlyIn9.class"), "OnlyIn9 (versions/9)");
            writeString(root.resolve("META-INF/versions/9/org/example/InBoth.class"), "InBoth (versions/9)");
            writeString(root.resolve("META-INF/versions/9/org/example/onlyIn9/OnlyIn9.class"), "OnlyIn9 (versions/9)");
            writeString(root.resolve("META-INF/versions/9/WEB-INF/classes/App.class"), "WEB-INF/classes/App.class (versions/9)");
            writeString(root.resolve("META-INF/versions/9/WEB-INF/lib/depend.jar"), "WEB-INF/lib/depend.jar (versions/9)");
            writeString(root.resolve("META-INF/versions/9/WEB-INF/web.xml"), "WEB-INF/web.xml (versions/9)");

            writeString(root.resolve("WEB-INF/classes/App.class"), "WEB-INF/classes/App.class (base)");
            writeString(root.resolve("WEB-INF/lib/depend.jar"), "WEB-INF/lib/depend.jar (base)");
            writeString(root.resolve("WEB-INF/web.xml"), "WEB-INF/web.xml (base)");

            writeString(root.resolve("org/example/InBoth$InnerBoth.class"), "InBoth$InnerBoth (base)");
            writeString(root.resolve("org/example/OnlyInBase.class"), "OnlyInBase (base)");
            writeString(root.resolve("org/example/InBoth.class"), "InBoth (base)");
            writeString(root.resolve("org/example/InBoth$InnerBase.class"), "InBoth$InnerBase (base)");
        }
    }

    private void writeString(Path outputPath, String contents) throws IOException
    {
        FS.ensureDirExists(outputPath.getParent());
        Files.writeString(outputPath, contents, StandardCharsets.UTF_8);
    }

    @Test
    public void testStreamAllFiles() throws Exception
    {
        Path exampleJar = workDir.getEmptyPathDir().resolve("multirelease.jar");
        createExampleJar(exampleJar);

        try (MultiReleaseJarFile jarFile = new MultiReleaseJarFile(exampleJar))
        {
            Set<String> actual = jarFile.stream()
                .filter(Files::isRegularFile)
                .map(Path::toString).collect(Collectors.toSet());
            String[] expected = {
                // exists in base only
                "/org/example/OnlyInBase.class",
                "/org/example/InBoth$InnerBase.class",
                // exists in versions/9
                "/org/example/Nowhere$NoOuter.class",
                "/org/example/InBoth$Inner9.class",
                "/org/example/OnlyIn9.class",
                "/org/example/onlyIn9/OnlyIn9.class",
                // exists in versions/10
                "/org/example/In10Only.class",
                // exists in base and is overridden by version specific entry
                "/org/example/InBoth.class",
                "/org/example/InBoth$InnerBoth.class",
                "/WEB-INF/web.xml",
                "/WEB-INF/classes/App.class",
                "/WEB-INF/lib/depend.jar",
                "/META-INF/MANIFEST.MF"
            };

            assertThat(actual, Matchers.containsInAnyOrder(expected));
        }
    }

    @Test
    public void testClassLoaderWithMultiReleaseBehaviors() throws Exception
    {
        Path exampleJar = workDir.getEmptyPathDir().resolve("multirelease.jar");
        createExampleJar(exampleJar);

        try (URLClassLoader loader = new URLClassLoader(new URL[]{exampleJar.toUri().toURL()}))
        {
            assertThat(readFile(loader.getResource("org/example/OnlyInBase.class")), is("OnlyInBase (base)"));
            assertThat(readFile(loader.getResource("org/example/OnlyIn9.class")), is("OnlyIn9 (versions/9)"));
            assertThat(readFile(loader.getResource("org/example/InBoth.class")), is("InBoth (versions/10)"));
            assertThat(readFile(loader.getResource("WEB-INF/web.xml")), is("WEB-INF/web.xml (versions/9)"));
            assertThat(readFile(loader.getResource("WEB-INF/classes/App.class")), is("WEB-INF/classes/App.class (versions/9)"));
            assertThat(readFile(loader.getResource("WEB-INF/lib/depend.jar")), is("WEB-INF/lib/depend.jar (versions/9)"));
        }
    }

    private String readFile(URL url) throws IOException
    {
        try (InputStream in = url.openStream())
        {
            return IO.toString(in, StandardCharsets.UTF_8);
        }
    }

    @Test
    public void testStreamAllFilesForEachEntries() throws IOException
    {
        List<String> entries = new ArrayList<>();

        Path exampleJar = workDir.getEmptyPathDir().resolve("multirelease.jar");
        createExampleJar(exampleJar);

        try (MultiReleaseJarFile jarFile = new MultiReleaseJarFile(exampleJar);
             Stream<Path> jarEntryStream = jarFile.stream()
                 .filter(Files::isRegularFile))
        {
            jarEntryStream.forEach(e ->
                entries.add(e.toString()));
        }

        String[] expected = {
            // exists in base only
            "/org/example/OnlyInBase.class",
            "/org/example/InBoth$InnerBase.class",
            // exists in versions/9
            "/org/example/Nowhere$NoOuter.class",
            "/org/example/InBoth$Inner9.class",
            "/org/example/OnlyIn9.class",
            "/org/example/onlyIn9/OnlyIn9.class",
            // exists in versions/10
            "/org/example/In10Only.class",
            // exists in base and is overridden by version specific entry
            "/org/example/InBoth.class",
            "/org/example/InBoth$InnerBoth.class",
            "/WEB-INF/web.xml",
            "/WEB-INF/classes/App.class",
            "/WEB-INF/lib/depend.jar",
            "/META-INF/MANIFEST.MF"
        };

        assertThat(entries, Matchers.containsInAnyOrder(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "foo/Bar.class",
        "Zed.class",
        "META-INF/versions/9/foo/Bar.class",
        "META-INF/versions/9/foo/module-info.class/Zed.class", // as path segment
        "", // no segment
    })
    public void testSkipModuleInfoClassTrue(String input)
    {
        Path path = workDir.getPath().resolve(input);
        assertTrue(MultiReleaseJarFile.skipModuleInfoClass(path), "skipModuleInfoClass(" + path + ")");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "foo/module-info.class",
        "module-info.class",
        "Module-Info.Class", // case differences
        "META-INF/versions/17/module-info.class"
    })
    public void testSkipModuleInfoClassFalse(String input)
    {
        Path path = workDir.getPath().resolve(input);
        assertFalse(MultiReleaseJarFile.skipModuleInfoClass(path), "skipModuleInfoClass(" + path + ")");
    }

    /**
     * Create an empty ZipFs FileSystem useful for testing skipMetaInfVersions and isMetaInfVersions rules
     */
    private static FileSystem getMetaInfVersionTestJar() throws IOException
    {
        if (metaInfVersionTestFileSystem != null)
            return metaInfVersionTestFileSystem;

        Path outputJar = MavenTestingUtils.getTargetTestingPath("getMetaInfVersionTestJar").resolve("metainf-versions.jar");
        FS.ensureEmpty(outputJar.getParent());

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        URI uri = URI.create("jar:" + outputJar.toUri().toASCIIString());
        metaInfVersionTestFileSystem = FileSystems.newFileSystem(uri, env);

        // this is an empty FileSystem, I don't need to create the files for the skipMetaInfVersions() tests

        return metaInfVersionTestFileSystem;
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/root.txt",
        "/META-INF/MANIFEST.MF",
        "/META-INF/services/versions/foo.txt",
        "/META-INF/versions/", // root, no version
        "/META-INF/versions/Zed.class", // root, no version
        "/meta-inf/versions/10/Foo.class", // not following case sensitivity rules in Java spec
        "/meta-inf/VERSIONS/10/Zed.class", // not following case sensitivity rules in Java spec
    })
    public void testNotMetaInfVersions(String input) throws IOException
    {
        FileSystem zipfs = getMetaInfVersionTestJar();
        Path testPath = zipfs.getPath(input);
        assertTrue(MultiReleaseJarFile.skipMetaInfVersions(testPath), "skipMetaInfVersions(" + testPath + ")");
        assertFalse(MultiReleaseJarFile.isMetaInfVersions(testPath), "isMetaInfVersions(" + testPath + ")");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/META-INF/versions/9/foo.txt",
        "/META-INF/versions/17/org/eclipse/demo/Util.class",
        "/META-INF/versions/17/WEB-INF/web.xml",
        "/META-INF/versions/10/module-info.class"
    })
    public void testIsMetaInfVersions(String input) throws IOException
    {
        FileSystem zipfs = getMetaInfVersionTestJar();
        Path testPath = zipfs.getPath(input);
        assertTrue(MultiReleaseJarFile.isMetaInfVersions(testPath), "isMetaInfVersions(" + testPath + ")");
        assertFalse(MultiReleaseJarFile.skipMetaInfVersions(testPath), "skipMetaInfVersions(" + testPath + ")");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Foo.class",
        "org/eclipse/jetty/demo/Zed.class",
        "org/eclipse/jetty/demo/Zed$Inner.class"
    })
    public void testIsClassFileTrue(String input)
    {
        Path base = MavenTestingUtils.getTargetTestingPath();
        Path testPath = base.resolve(input);
        assertTrue(MultiReleaseJarFile.isClassFile(testPath), "isClassFile(" + testPath + ")");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // Doesn't end in class
        "Foo.txt",
        // No name
        ".class",
        // Illegal characters
        "tab\tcharacter.class",
        // Doesn't start with identifier
        "42.class",
        "org/eclipse/jetty/demo/123Foo.class",
        // Has spaces
        "org/eclipse/jetty/demo/A $ Inner.class"
    })
    public void testIsClassFileFalse(String input)
    {
        Path base = MavenTestingUtils.getTargetTestingPath();
        Path testPath = base.resolve(input);
        assertFalse(MultiReleaseJarFile.isClassFile(testPath), "isClassFile(" + testPath + ")");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        ".dir/foo.txt",
        ".bar",
        "a/b/c/.d/e/f/g.jpeg"
    })
    @EnabledOnOs({OS.LINUX, OS.MAC})
    public void testIsHiddenTrue(String input) throws IOException
    {
        Path base = MavenTestingUtils.getTargetTestingPath("testIsHiddenTrue");
        Path path = base.resolve(input);
        FS.ensureDirExists(path.getParent());
        FS.touch(path);

        assertTrue(MultiReleaseJarFile.isHidden(base, path), "isHidden(" + input + ")");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "dir/foo.txt",
        "bar",
        "zed.png",
        "a/b/c/d/e/f/g.jpeg"
    })
    public void testIsHiddenFalse(String input) throws IOException
    {
        Path base = MavenTestingUtils.getTargetTestingPath("testIsHiddenFalse");
        Path path = base.resolve(input);
        FS.ensureDirExists(path.getParent());
        FS.touch(path);

        assertFalse(MultiReleaseJarFile.isHidden(base, path), "isHidden(" + input + ")");
    }
}
