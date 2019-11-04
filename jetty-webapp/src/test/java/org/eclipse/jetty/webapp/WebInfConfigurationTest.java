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
import org.eclipse.jetty.util.JavaVersion;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * WebInfConfigurationTest
 */
@ExtendWith(WorkDirExtension.class)
public class WebInfConfigurationTest
{
    private static final Logger LOG = Log.getLogger(WebInfConfigurationTest.class);
    private static final String TEST_RESOURCE_JAR = "test-base-resource.jar";

    public WorkDir workDir;

    /**
     * Assume target < jdk9. In this case, we should be able to extract
     * the urls from the application classloader, and we should not look
     * at the java.class.path property.
     */
    @Test
    @EnabledOnJre(JRE.JAVA_8)
    public void testFindAndFilterContainerPaths()
        throws Exception
    {
        WebInfConfiguration config = new WebInfConfiguration();
        WebAppContext context = new WebAppContext();
        context.setAttribute(WebInfConfiguration.CONTAINER_JAR_PATTERN, ".*/jetty-util-[^/]*\\.jar$|.*/jetty-util/target/classes/");

        WebAppClassLoader loader = new WebAppClassLoader(context);
        context.setClassLoader(loader);
        config.findAndFilterContainerPaths(context);
        List<Resource> containerResources = context.getMetaData().getContainerResources();
        assertEquals(1, containerResources.size());
        assertThat(containerResources.get(0).toString(), containsString("jetty-util"));
    }

    /**
     * Assume target jdk9 or above. In this case we should extract what we need
     * from the java.class.path. We should also examine the module path.
     */
    @Test
    @DisabledOnJre(JRE.JAVA_8)
    @EnabledIfSystemProperty(named = "jdk.module.path", matches = ".*")
    public void testFindAndFilterContainerPathsJDK9()
        throws Exception
    {
        WebInfConfiguration config = new WebInfConfiguration();
        WebAppContext context = new WebAppContext();
        context.setAttribute(WebInfConfiguration.CONTAINER_JAR_PATTERN, ".*/jetty-util-[^/]*\\.jar$|.*/jetty-util/target/classes/$|.*/foo-bar-janb.jar");
        WebAppClassLoader loader = new WebAppClassLoader(context);
        context.setClassLoader(loader);
        config.findAndFilterContainerPaths(context);
        List<Resource> containerResources = context.getMetaData().getContainerResources();
        assertEquals(2, containerResources.size());
        for (Resource r : containerResources)
        {
            String s = r.toString();
            assertThat(s, anyOf(endsWith("foo-bar-janb.jar"), containsString("jetty-util")));
        }
    }

    /**
     * Assume runtime is jdk9 or above. Target is jdk 8. In this
     * case we must extract from the java.class.path (because jdk 9
     * has no url based application classloader), but we should
     * ignore the module path.
     */
    @Test
    @DisabledOnJre(JRE.JAVA_8)
    @EnabledIfSystemProperty(named = "jdk.module.path", matches = ".*")
    public void testFindAndFilterContainerPathsTarget8()
        throws Exception
    {
        WebInfConfiguration config = new WebInfConfiguration();
        WebAppContext context = new WebAppContext();
        context.setAttribute(JavaVersion.JAVA_TARGET_PLATFORM, "8");
        context.setAttribute(WebInfConfiguration.CONTAINER_JAR_PATTERN, ".*/jetty-util-[^/]*\\.jar$|.*/jetty-util/target/classes/$|.*/foo-bar-janb.jar");
        WebAppClassLoader loader = new WebAppClassLoader(context);
        context.setClassLoader(loader);
        config.findAndFilterContainerPaths(context);
        List<Resource> containerResources = context.getMetaData().getContainerResources();
        assertEquals(1, containerResources.size());
        assertThat(containerResources.get(0).toString(), containsString("jetty-util"));
    }

    public static Stream<Arguments> baseResourceNames()
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
    @MethodSource("baseResourceNames")
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

    /**
     * Similar to testPathGetResourceBaseName, but with "file:" URIs
     */
    @ParameterizedTest
    @MethodSource("baseResourceNames")
    public void testFileUriGetUriLastPathSegment(String basePath, String expectedName) throws IOException
    {
        Path root = workDir.getPath();
        Path base = root.resolve(basePath);
        URI uri = base.toUri();
        if (OS.MAC.isCurrentOs())
        {
            // Normalize Unicode to NFD form that OSX Path/FileSystem produces
            expectedName = Normalizer.normalize(expectedName, Normalizer.Form.NFD);
        }
        assertThat(WebInfConfiguration.getUriLastPathSegment(uri), is(expectedName));
    }

    public static Stream<Arguments> jarFileBaseResourceNames() throws URISyntaxException, IOException
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
                        String lastPathSegment = TEST_RESOURCE_JAR;
                        if (path.getFileName() != null)
                        {
                            lastPathSegment = path.getFileName().toString();
                        }
                        // Strip last '/' from directory entries
                        if (Files.isDirectory(path) && lastPathSegment.endsWith("/"))
                        {
                            lastPathSegment = lastPathSegment.substring(0, lastPathSegment.length() - 1);
                        }
                        arguments.add(Arguments.of(path.toUri(), lastPathSegment));
                    }
                });
            }
        }

        return arguments.stream();
    }

    /**
     * Tests of "jar:file:" based URIs
     */
    @ParameterizedTest
    @MethodSource("jarFileBaseResourceNames")
    public void testJarFileUriGetUriLastPathSegment(URI uri, String expectedName) throws IOException
    {
        assertThat(WebInfConfiguration.getUriLastPathSegment(uri), is(expectedName));
    }
}
