//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.JavaVersion;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;
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
        context.setAttribute(WebInfConfiguration.CONTAINER_JAR_PATTERN, ".*/jetty-util-[0-9][^/]*\\.jar$|.*/jetty-util/target/classes/");

        WebAppClassLoader loader = new WebAppClassLoader(context);
        context.setClassLoader(loader);
        config.findAndFilterContainerPaths(context);
        List<Resource> containerResources = context.getMetaData().getContainerResources();
        assertEquals(1, containerResources.size(), () -> containerResources.stream().map(Resource::toString).collect(Collectors.joining(",", "[", "]")));
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
        context.setAttribute(WebInfConfiguration.CONTAINER_JAR_PATTERN, ".*/jetty-util-[0-9][^/]*\\.jar$|.*/jetty-util/target/classes/$|.*/foo-bar-janb.jar");
        WebAppClassLoader loader = new WebAppClassLoader(context);
        context.setClassLoader(loader);
        config.findAndFilterContainerPaths(context);
        List<Resource> containerResources = context.getMetaData().getContainerResources();
        assertEquals(2, containerResources.size(), () -> containerResources.stream().map(Resource::toString).collect(Collectors.joining(",", "[", "]")));
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
        context.setAttribute(WebInfConfiguration.CONTAINER_JAR_PATTERN, ".*/jetty-util-[0-9][^/]*\\.jar$|.*/jetty-util/target/classes/$|.*/foo-bar-janb.jar");
        WebAppClassLoader loader = new WebAppClassLoader(context);
        context.setClassLoader(loader);
        config.findAndFilterContainerPaths(context);
        List<Resource> containerResources = context.getMetaData().getContainerResources();
        assertEquals(1, containerResources.size(), () -> containerResources.stream().map(Resource::toString).collect(Collectors.joining(",", "[", "]")));
        assertThat(containerResources.get(0).toString(), containsString("jetty-util"));
    }

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
}
