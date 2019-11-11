//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.webapp;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * WebInfConfigurationTest
 */
@ExtendWith(WorkDirExtension.class)
public class WebInfConfigurationTest
{
    private static final Logger LOG = Log.getLogger(WebInfConfigurationTest.class);
    private static final String TEST_RESOURCE_JAR = "test-base-resource.jar";

    public WorkDir workDir;

    public static Stream<Arguments> rawResourceNames()
    {
        return Stream.of(
            Arguments.of("/", ""),
            Arguments.of("/a", "a")
        );
    }

    @ParameterizedTest
    @MethodSource("rawResourceNames")
    public void testTinyGetResourceBaseName(String rawPath, String expectedName) throws IOException
    {
        Resource resource = Resource.newResource(rawPath);
        assertThat(WebInfConfiguration.getResourceBaseName(resource), is(expectedName));
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
    public void testPathGetResourceBaseName(String basePath, String expectedName) throws IOException
    {
        Path root = workDir.getPath();
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

        Resource resource = new PathResource(base);
        assertThat(WebInfConfiguration.getResourceBaseName(resource), is(expectedName));
    }

    public static Stream<Arguments> fileUriBaseResourceNames()
    {
        return Stream.of(
            Arguments.of("test.war", "test.war"),
            Arguments.of("a/b/c/test.war", "test.war"),
            Arguments.of("bar%2Fbaz/test.war", "test.war"),
            Arguments.of("fizz buzz/test.war", "test.war"),
            Arguments.of("another one/bites the dust/", "bites the dust"),
            Arguments.of("another+one/bites+the+dust/", "bites+the+dust"),
            Arguments.of("another%20one/bites%20the%20dust/", "bites%20the%20dust"),
            Arguments.of("spanish/n\u00FAmero.war", "n\u00FAmero.war"),
            Arguments.of("spanish/n%C3%BAmero.war", "n%C3%BAmero.war"),
            Arguments.of("a/b!/", "b"),
            Arguments.of("a/b!/c/", "b"),
            Arguments.of("a/b!/c/d/", "b"),
            Arguments.of("a/b%21/", "b%21")
        );
    }

    /**
     * Similar to testPathGetResourceBaseName, but with "file:" URIs
     */
    @ParameterizedTest
    @MethodSource("fileUriBaseResourceNames")
    public void testFileUriGetUriLastPathSegment(String basePath, String expectedName) throws IOException
    {
        Path root = workDir.getPath();
        Path base = root.resolve(basePath);
        if (basePath.endsWith("/"))
        {
            FS.ensureDirExists(base);
        }
        else
        {
            FS.ensureDirExists(base.getParent());
            FS.touch(base);
        }
        URI uri = base.toUri();
        if (OS.MAC.isCurrentOs())
        {
            // Normalize Unicode to NFD form that OSX Path/FileSystem produces
            expectedName = Normalizer.normalize(expectedName, Normalizer.Form.NFD);
        }
        assertThat(WebInfConfiguration.getUriLastPathSegment(uri), is(expectedName));
    }

    public static Stream<Arguments> uriLastSegmentSource() throws URISyntaxException, IOException
    {
        Path testJar = MavenTestingUtils.getTestResourcePathFile(TEST_RESOURCE_JAR);
        URI uri = new URI("jar", testJar.toUri().toASCIIString(), null);

        Map<String, Object> env = new HashMap<>();
        env.put("multi-release", "runtime");

        List<Arguments> arguments = new ArrayList<>();
        arguments.add(Arguments.of(uri, TEST_RESOURCE_JAR));
        try (FileSystem zipFs = FileSystems.newFileSystem(uri, env))
        {
            FileVisitOption[] fileVisitOptions = new FileVisitOption[]{};

            for (Path root : zipFs.getRootDirectories())
            {
                Stream<Path> entryStream = Files.find(root, 10, (path, attrs) -> true, fileVisitOptions);
                entryStream.forEach((path) ->
                {
                    if (path.toString().endsWith("!/"))
                    {
                        // skip - JAR entry type not supported by Jetty
                        // TODO: re-enable once we start to use zipfs
                        LOG.warn("Skipping Unsupported entry: " + path.toUri());
                    }
                    else
                    {
                        arguments.add(Arguments.of(path.toUri(), TEST_RESOURCE_JAR));
                    }
                });
            }
        }

        return arguments.stream();
    }

    /**
     * Tests of URIs last segment, including "jar:file:" based URIs.
     */
    @ParameterizedTest
    @MethodSource("uriLastSegmentSource")
    public void testGetUriLastPathSegment(URI uri, String expectedName) throws IOException
    {
        assertThat(WebInfConfiguration.getUriLastPathSegment(uri), is(expectedName));
    }
}
