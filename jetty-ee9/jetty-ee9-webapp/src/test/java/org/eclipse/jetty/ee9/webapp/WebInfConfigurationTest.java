//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.webapp;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import jakarta.servlet.ServletContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebInfConfigurationTest
 */
@ExtendWith(WorkDirExtension.class)
public class WebInfConfigurationTest
{
    private static List<String> EXPECTED_JAR_NAMES = Arrays.asList(new String[]{"alpha.jar", "omega.jar", "acme.jar"});
    private Server _server;

    @AfterEach
    public void afterEach() throws Exception
    {
        if (_server == null)
            return;
        _server.stop();
    }

    private static Path createWar(Path tempDir, String name) throws Exception
    {
        // Create war on the fly
        Path testWebappDir = MavenTestingUtils.getTargetPath("test-classes/webapp");
        assertTrue(Files.exists(testWebappDir));
        Path warFile = tempDir.resolve(name);

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        URI uri = URI.create("jar:" + warFile.toUri().toASCIIString());
        // Use ZipFS so that we can create paths that are just "/"
        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env))
        {
            Path root = zipfs.getPath("/");
            IO.copyDir(testWebappDir, root);
        }

        return warFile;
    }

    public static Stream<Arguments> fileBaseResourceNames()
    {
        return Stream.of(
            Arguments.of("test.war", "test.war"),
            Arguments.of("a/b/c/test.war", "test.war"),
            Arguments.of("bar%2Fbaz/test.war", "test.war"),
            Arguments.of("fizz buzz/test.war", "test.war"),
            Arguments.of("another one/bites the dust/", "bites the dust"),
            Arguments.of("another+one/bites+the+dust/", "bites+the+dust"),
            Arguments.of("another%20one/bites%20the%20dust/", "bites%20the%20dust"),
            // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
            Arguments.of("spanish/n\u00FAmero.war", "n\u00FAmero.war"),
            Arguments.of("spanish/n%C3%BAmero.war", "n%C3%BAmero.war"),
            Arguments.of("a/b!/", "b!"),
            Arguments.of("a/b!/c/", "c"),
            Arguments.of("a/b!/c/d/", "d"),
            Arguments.of("a/b%21/", "b%21")
        );
    }

    @ParameterizedTest
    @MethodSource("fileBaseResourceNames")
    public void testPathGetResourceBaseName(String basePath, String expectedName, WorkDir workDir) throws IOException
    {
        Path root = workDir.getEmptyPathDir();
        Path base = root.resolve(basePath);
        if (basePath.endsWith("/"))
        {
            // we are working with a directory.
            FS.ensureDirExists(base);
        }
        else
        {
            FS.ensureDirExists(base.getParent());
            FS.touch(base);
        }

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resource = resourceFactory.newResource(base);
            assertThat(WebInfConfiguration.getResourceBaseName(resource), is(expectedName));
        }
    }

    /**
     * Test that if the WebAppContext is configured NOT to extract anything,
     * nothing is extracted.
     */
    @Test
    public void testShouldNotUnpackWar(WorkDir workDir) throws Exception
    {
        Path testPath = MavenPaths.targetTestDir("testSimple");
        FS.ensureDirExists(testPath);
        FS.ensureEmpty(testPath);

        _server = new Server();
        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        Path warPath = createWar(testPath, "test.war");
        context.setExtractWAR(false);
        context.setCopyWebDir(false);
        context.setCopyWebInf(false);
        context.setWar(warPath.toUri().toURL().toString());
        _server.setHandler(context);
        _server.start();
        Path unpackedDir = context.getTempDirectory().toPath().resolve("webapp");
        Path unpackedWebInfDir = context.getTempDirectory().toPath().resolve("webinf");
        assertFalse(Files.exists(unpackedDir)); //should not have unpacked
        assertFalse(Files.exists(unpackedWebInfDir)); //should not have unpacked
    }

    /**
     * Test that if the war should be extracted, it is.
     */
    @Test
    public void testShouldUnpackWar(WorkDir workDir) throws Exception
    {
        Path testPath = MavenPaths.targetTestDir("testSimple");
        FS.ensureDirExists(testPath);
        FS.ensureEmpty(testPath);

        _server = new Server();
        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        Path warPath = createWar(testPath, "test.war");
        context.setExtractWAR(true);
        context.setCopyWebDir(false);
        context.setCopyWebInf(false);
        context.setWar(warPath.toUri().toURL().toString());
        _server.setHandler(context);
        _server.start();
        Path unpackedDir = context.getTempDirectory().toPath().resolve("webapp");
        Path unpackedWebInfDir = context.getTempDirectory().toPath().resolve("webinf");
        assertTrue(Files.exists(unpackedDir)); //should have unpacked whole war
        assertFalse(Files.exists(unpackedWebInfDir)); //should not have re-unpacked WEB-INF
        checkNoDuplicateJars(EXPECTED_JAR_NAMES, (URLClassLoader)context.getClassLoader());
    }

    /**
     * Test not unpacking the whole war, just WEB-INF
     */
    @Test
    public void testShouldUnpackWebInfOnly(WorkDir workDir)  throws Exception
    {
        Path testPath = MavenPaths.targetTestDir("testSimple");
        FS.ensureDirExists(testPath);
        FS.ensureEmpty(testPath);

        _server = new Server();
        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        Path warPath = createWar(testPath, "test.war");
        context.setExtractWAR(false);
        context.setCopyWebDir(false);
        context.setCopyWebInf(true);
        context.setWar(warPath.toUri().toURL().toString());
        _server.setHandler(context);
        _server.start();
        Path unpackedDir = context.getTempDirectory().toPath().resolve("webapp");
        Path unpackedWebInfDir = context.getTempDirectory().toPath().resolve("webinf");
        assertFalse(Files.exists(unpackedDir)); //should not have unpacked whole war
        assertTrue(Files.exists(unpackedWebInfDir)); //should have unpacked WEB-INF
        assertTrue(Files.exists(unpackedWebInfDir.resolve("WEB-INF").resolve("lib").resolve("alpha.jar")));

        checkNoDuplicateJars(EXPECTED_JAR_NAMES, (URLClassLoader)context.getClassLoader());
    }

    /**
     * Odd combination, but it has always been available: test that both the
     * whole war can be extracted, _and_ WEB-INF separately.
     */
    @Test
    public void testShouldUnpackWarAndWebInf(WorkDir workDir) throws Exception
    {
        Path testPath = MavenPaths.targetTestDir("testSimple");
        FS.ensureDirExists(testPath);
        FS.ensureEmpty(testPath);

        _server = new Server();
        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        Path warPath = createWar(testPath, "test.war");
        context.setExtractWAR(true);
        context.setCopyWebDir(false);
        context.setCopyWebInf(true);
        context.setWar(warPath.toUri().toURL().toString());
        _server.setHandler(context);
        _server.start();
        Path unpackedDir = context.getTempDirectory().toPath().resolve("webapp");
        Path unpackedWebInfDir = context.getTempDirectory().toPath().resolve("webinf");
        assertTrue(Files.exists(unpackedDir)); //should have unpacked whole war
        assertTrue(Files.exists(unpackedWebInfDir)); //should have re-unpacked WEB-INF
        assertTrue(Files.exists(unpackedWebInfDir.resolve("WEB-INF").resolve("lib").resolve("alpha.jar")));
    }

    @Test
    public void testResolveTempDirectory(WorkDir workDir) throws Exception
    {
        Path testPath = MavenPaths.targetTestDir("testSimple");
        FS.ensureDirExists(testPath);
        FS.ensureEmpty(testPath);

        _server = new Server();
        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        Path warPath = createWar(testPath, "test.war");
        context.setExtractWAR(true);
        context.setWar(warPath.toUri().toURL().toString());
        _server.setHandler(context);
        _server.start();
        File tmpDir = context.getTempDirectory();
        assertNotNull(tmpDir);

        Path tmpPath = tmpDir.toPath();
        Path lastName = tmpPath.getName(tmpPath.getNameCount() - 1);
        assertThat(lastName.toString(), startsWith("jetty-test_war-_-any-"));
        assertThat(context.getAttribute(ServletContext.TEMPDIR), is(tmpDir));
    }

    /**
     * Assert that for each of the expected jar names (stripped of any path info),
     * there is only 1 actual jar url in the context classloader
     *
     * @param expected name of a jar without any preceding path info eg "foo.jar"
     * @param classLoader the context classloader
     */
    private void checkNoDuplicateJars(List<String> expected, URLClassLoader classLoader)
    {
        List<String> actual = new ArrayList<>();
        for (URL u : classLoader.getURLs())
            actual.add(u.toExternalForm());

        for (String e : expected)
        {
            long count = actual.stream().filter(s -> s.endsWith(e)).count();
            assertThat(count, equalTo(1L));
        }
    }
}
