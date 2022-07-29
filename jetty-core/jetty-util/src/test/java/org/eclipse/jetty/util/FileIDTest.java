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
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class FileIDTest
{
    private static FileSystem metaInfVersionTestFileSystem;
    public WorkDir workDir;

    @AfterAll
    public static void closeFileSystems()
    {
        IO.close(metaInfVersionTestFileSystem);
    }

    private Path touchTestPath(String input) throws IOException
    {
        return touchTestPath(workDir.getPath(), input);
    }

    private Path touchTestPath(Path base, String input) throws IOException
    {
        Path path = base.resolve(input);
        if (input.endsWith("/"))
        {
            FS.ensureEmpty(path);
        }
        else
        {
            FS.ensureDirExists(path.getParent());
            FS.touch(path);
        }
        return path;
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

    public static Stream<Arguments> basenameCases()
    {
        return Stream.of(
            Arguments.of("foo.xml", "foo"),
            Arguments.of("dir/foo.xml", "foo"),
            Arguments.of("dir/foo", "foo"),
            Arguments.of("foo", "foo")
        );
    }

    @ParameterizedTest
    @MethodSource("basenameCases")
    public void testGetBasename(String input, String expected)
    {
        Path path = workDir.getEmptyPathDir().resolve(input);
        String actual = FileID.getBasename(path);
        assertThat(actual, is(expected));
    }

    public static Stream<Arguments> containsDirectoryTrueCases()
    {
        return Stream.of(
            Arguments.of("path/to/webapps/root.war", "webapps"),
            Arguments.of("path/to/webapps/", "webapps"),
            Arguments.of("META-INF/services/org.eclipse.jetty.FooService", "META-INF"),
            Arguments.of("META-INF/lib/lib-1.jar", "META-INF"),
            Arguments.of("deeper/path/to/exploded-jar/META-INF/lib/lib-1.jar", "META-INF")
        );
    }

    @ParameterizedTest
    @MethodSource("containsDirectoryTrueCases")
    public void testContainsDirectoryTrue(String input, String dirname) throws IOException
    {
        Path path = touchTestPath(input);
        assertTrue(FileID.containsDirectory(path, dirname), "containsDirectory(%s, \"%s\")".formatted(path, dirname));
    }

    public static Stream<Arguments> containsDirectoryFalseCases()
    {
        return Stream.of(
            Arguments.of("path/to/webapps/root.war", "WEB-INF"),
            Arguments.of("path/to/webapps/", "WEB-INF"),
            Arguments.of("classes/org.eclipse.jetty.util.Foo", "util"),
            Arguments.of("path/lib-a/foo.txt", "lib")
        );
    }

    @ParameterizedTest
    @MethodSource("containsDirectoryFalseCases")
    public void testContainsDirectoryFalse(String input, String dirname) throws IOException
    {
        Path path = touchTestPath(input);
        assertFalse(FileID.containsDirectory(path, dirname), "containsDirectory(%s, \"%s\")".formatted(path, dirname));
    }

    public static Stream<Arguments> extensionCases()
    {
        return Stream.of(
            Arguments.of("foo.xml", ".xml"),
            Arguments.of("dir/foo.xml", ".xml"),
            Arguments.of("foo.jar", ".jar"),
            Arguments.of("FOO.WAR", ".war"),
            Arguments.of("Foo.Zip", ".zip")
        );
    }

    @ParameterizedTest
    @MethodSource("extensionCases")
    public void testGetExtension(String input, String expected) throws IOException
    {
        String actual;

        actual = FileID.getExtension(input);
        assertThat("getExtension((String) \"%s\")".formatted(input), actual, is(expected));
        Path path = touchTestPath(input);
        actual = FileID.getExtension(path);
        assertThat("getExtension((Path) \"%s\")".formatted(path), actual, is(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "jar:file:/home/user/project/with.jar/in/path/name",
        "file:/home/user/project/directory/",
        "file:/home/user/hello.ear",
        "/home/user/hello.jar",
        "/home/user/app.war"
    })
    public void testIsArchiveUriFalse(String rawUri)
    {
        assertFalse(FileID.isArchive(URI.create(rawUri)), "Should be detected as a JAR URI: " + rawUri);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "file:/home/user/.m2/repository/com/company/1.0/company-1.0.jar",
        "jar:file:/home/user/.m2/repository/com/company/1.0/company-1.0.jar!/",
        "jar:file:/home/user/.m2/repository/com/company/1.0/company-1.0.jar",
        "file:/home/user/install/jetty-home-12.0.0.zip",
        "file:/opt/websites/webapps/company.war",
        "jar:file:/home/user/.m2/repository/jakarta/servlet/jakarta.servlet-api/6.0.0/jakarta.servlet-api-6.0.0.jar!/META-INF/resources"
    })
    public void testIsArchiveUriTrue(String rawUri)
    {
        assertTrue(FileID.isArchive(URI.create(rawUri)), "Should be detected as a JAR URI: " + rawUri);
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
    public void testIsClassFileFalse(String input) throws IOException
    {
        Path testPath = touchTestPath(input);
        assertFalse(FileID.isClassFile(testPath), "isClassFile(" + testPath + ")");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Foo.class",
        "org/eclipse/jetty/demo/Zed.class",
        "org/eclipse/jetty/demo/Zed$Inner.class"
    })
    public void testIsClassFileTrue(String input) throws IOException
    {
        Path testPath = touchTestPath(input);
        assertTrue(FileID.isClassFile(testPath), "isClassFile(" + testPath + ")");
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
        Path base = workDir.getEmptyPathDir();
        Path path = touchTestPath(base, input);
        assertFalse(FileID.isHidden(base, path), "isHidden(" + input + ")");
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
        Path base = workDir.getEmptyPathDir();
        Path path = touchTestPath(base, input);
        assertTrue(FileID.isHidden(base, path), "isHidden(" + input + ")");
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
        assertTrue(FileID.isMetaInfVersions(testPath), "isMetaInfVersions(" + testPath + ")");
        assertFalse(FileID.skipMetaInfVersions(testPath), "skipMetaInfVersions(" + testPath + ")");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "foo",
        "dir/",
        "zed.txt",
        "dir/zed.txt",
        "dir/bar.war/zed.txt",
        "dir/bar.war/",
        "cee.jar",
        "cee.zip"
    })
    public void testIsWebArchiveFalse(String input) throws IOException
    {
        assertFalse(FileID.isWebArchive(input), "isWebArchive((String) \"%s\")".formatted(input));
        Path path = touchTestPath(input);
        assertFalse(FileID.isWebArchive(path), "isWebArchive((Path) \"%s\")".formatted(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "dir/foo.war",
        "DIR/FOO.WAR",
        "Dir/Foo.War",
        "zed.war",
        "ZED.WAR",
        "Zed.War"
    })
    public void testIsWebArchiveTrue(String input) throws IOException
    {
        assertTrue(FileID.isWebArchive(input), "isWebArchive((String) \"%s\")".formatted(input));
        Path path = touchTestPath(input);
        assertTrue(FileID.isWebArchive(path), "isWebArchive((Path) \"%s\")".formatted(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "foo.jar",
        "FOO.war",
        "Foo.zip",
        "dir/zed.xml/",
        "dir/zed.xml/bar.txt"
    })
    public void testIsXmlFalse(String input) throws IOException
    {
        assertFalse(FileID.isXml(input), "isXml((String) \"%s\")".formatted(input));
        Path path = touchTestPath(input);
        assertFalse(FileID.isXml(path), "isXml((Path) \"%s\")".formatted(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "foo.xml",
        "FOO.XML",
        "Foo.Xml",
        "dir/zed.xml",
        "DIR/ZED.XML",
        "Dir/Zed.Xml",
    })
    public void testIsXmlTrue(String input) throws IOException
    {
        assertTrue(FileID.isXml(input), "isXml((String) \"%s\")".formatted(input));
        Path path = touchTestPath(input);
        assertTrue(FileID.isXml(path), "isXml((Path) \"%s\")".formatted(path));
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
        assertTrue(FileID.skipMetaInfVersions(testPath), "skipMetaInfVersions(" + testPath + ")");
        assertFalse(FileID.isMetaInfVersions(testPath), "isMetaInfVersions(" + testPath + ")");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "foo/module-info.class",
        "module-info.class",
        "Module-Info.Class", // case differences
        "META-INF/versions/17/module-info.class"
    })
    public void testSkipModuleInfoClassFalse(String input) throws IOException
    {
        Path path = touchTestPath(input);
        assertFalse(FileID.skipModuleInfoClass(path), "skipModuleInfoClass(" + path + ")");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "foo/Bar.class",
        "Zed.class",
        "META-INF/versions/9/foo/Bar.class",
        "META-INF/versions/9/foo/module-info.class/Zed.class", // as path segment
        "", // no segment
    })
    public void testSkipModuleInfoClassTrue(String input) throws IOException
    {
        Path path = touchTestPath(input);
        assertTrue(FileID.skipModuleInfoClass(path), "skipModuleInfoClass(" + path + ")");
    }
}
