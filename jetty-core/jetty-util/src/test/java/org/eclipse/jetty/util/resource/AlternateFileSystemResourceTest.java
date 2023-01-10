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

package org.eclipse.jetty.util.resource;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.util.Optional;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test to ensure that Alternate FileSystem providers work as expected.
 *
 * <p>
 * Uses the <a href="https://github.com/google/jimfs">google/jimfs</a> In-Memory FileSystem provider
 * to have a FileSystem based on scheme `jimfs` (with an authority)
 * </p>
 */
public class AlternateFileSystemResourceTest
{
    private static final Logger LOG = LoggerFactory.getLogger(AlternateFileSystemResourceTest.class);
    private FileSystem jimfs;
    private URI fsBaseURI;

    @BeforeEach
    public void initInMemoryFileSystem(TestInfo testInfo)
    {
        Optional<Method> testMethod = testInfo.getTestMethod();
        String testMethodName = testMethod.map(Method::getName).orElseGet(AlternateFileSystemResourceTest.class::getSimpleName);
        jimfs = Jimfs.newFileSystem(testMethodName, Configuration.unix());
        fsBaseURI = jimfs.getPath("/").toUri();

        ResourceFactory.registerResourceFactory(fsBaseURI.getScheme(), new MountedPathResourceFactory());
    }

    @AfterEach
    public void closeInMemoryFileSystem()
    {
        IO.close(jimfs);
    }

    @Test
    public void testNewResource() throws IOException
    {
        // Create some content to reference
        Files.writeString(jimfs.getPath("/foo.txt"), "Hello Foo", StandardCharsets.UTF_8);

        // Reference it via Resource object
        Resource resource = ResourceFactory.root().newResource(fsBaseURI.resolve("/foo.txt"));
        assertTrue(Resources.isReadable(resource));

        LOG.info("resource = {}", resource);

        try (InputStream in = resource.newInputStream())
        {
            String contents = IO.toString(in, StandardCharsets.UTF_8);
            assertThat(contents, is("Hello Foo"));
        }
    }

    @Test
    public void testNavigateResource() throws IOException
    {
        // Create some content to reference
        Files.createDirectories(jimfs.getPath("/zed"));
        Files.writeString(jimfs.getPath("/zed/bar.txt"), "Hello Bar", StandardCharsets.UTF_8);

        // Reference it via Resource object
        Resource resourceRoot = ResourceFactory.root().newResource(fsBaseURI.resolve("/"));
        assertTrue(Resources.isDirectory(resourceRoot));

        Resource resourceZedDir = resourceRoot.resolve("zed");
        assertTrue(Resources.isDirectory(resourceZedDir));

        Resource resourceBarText = resourceZedDir.resolve("bar.txt");
        LOG.info("resource = {}", resourceBarText);

        try (InputStream in = resourceBarText.newInputStream())
        {
            String contents = IO.toString(in, StandardCharsets.UTF_8);
            assertThat(contents, is("Hello Bar"));
        }
    }
}
