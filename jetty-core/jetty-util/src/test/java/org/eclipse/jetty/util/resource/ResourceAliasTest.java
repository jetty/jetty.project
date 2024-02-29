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

package org.eclipse.jetty.util.resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class ResourceAliasTest
{
    private static final Logger LOG = LoggerFactory.getLogger(ResourceAliasTest.class);

    private final ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable();

    @BeforeEach
    public void beforeEach()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @AfterEach
    public void afterEach()
    {
        resourceFactory.close();
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @Test
    public void testAliasNavigation(WorkDir workDir) throws IOException
    {
        Path docroot = workDir.getEmptyPathDir();
        Path dir = docroot.resolve("dir");
        Files.createDirectory(dir);

        Path foo = docroot.resolve("foo");
        Files.createDirectory(foo);

        Path testText = dir.resolve("test.txt");
        Files.writeString(testText, "Contents of test.txt", StandardCharsets.UTF_8);

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource rootRes = resourceFactory.newResource(docroot);
            // Test navigation through a directory that doesn't exist
            Resource fileResViaBar = rootRes.resolve("bar/../dir/test.txt");
            if (OS.WINDOWS.isCurrentOs()) // windows allows navigation through a non-existent directory
                assertTrue(Resources.exists(fileResViaBar));
            else
                assertTrue(Resources.missing(fileResViaBar), "File doesn't exist");

            // Test navigation through a directory that does exist
            Resource fileResViaFoo = rootRes.resolve("foo/../dir/test.txt");
            assertTrue(fileResViaFoo.exists(), "Should exist");
            assertTrue(fileResViaFoo.isAlias(), "Should be an alias");
        }
    }

    @Test
    public void testPercentPaths(WorkDir workDir) throws IOException
    {
        Path baseDir = workDir.getEmptyPathDir();
        Path foo = baseDir.resolve("%foo");
        Files.createDirectories(foo);

        Path bar = foo.resolve("bar%");
        Files.createDirectories(bar);

        Path text = bar.resolve("test.txt");
        FS.touch(text);

        // At this point we have a path .../%foo/bar%/test.txt present on the filesystem.
        // This would also apply for paths found in JAR files (like META-INF/resources/%foo/bar%/test.txt)

        assertTrue(Files.exists(text));

        Resource baseResource = resourceFactory.newResource(baseDir);
        assertTrue(baseResource.exists(), "baseResource exists");

        Resource fooResource = baseResource.resolve("%25foo");
        assertTrue(fooResource.exists(), "fooResource exists");
        assertTrue(fooResource.isDirectory(), "fooResource isDir");
        assertFalse(fooResource.isAlias(), "fooResource isAlias");

        Resource barResource = fooResource.resolve("bar%25");
        assertTrue(barResource.exists(), "barResource exists");
        assertTrue(barResource.isDirectory(), "barResource isDir");
        assertFalse(barResource.isAlias(), "barResource isAlias");

        Resource textResource = barResource.resolve("test.txt");
        assertTrue(textResource.exists(), "textResource exists");
        assertFalse(textResource.isDirectory(), "textResource isDir");
    }

    @Test
    public void testNullCharEndingFilename(WorkDir workDir) throws Exception
    {
        Path baseDir = workDir.getEmptyPathDir();
        Path file = baseDir.resolve("test.txt");
        FS.touch(file);

        try
        {
            Path file0 = baseDir.resolve("test.txt\0");
            if (!Files.exists(file0))
                return;  // this file system does get tricked by ending filenames

            assertThat(file0 + " exists", Files.exists(file0), is(true));  // This is an alias!

            Resource dir = resourceFactory.newResource(baseDir);

            // Test not alias paths
            Resource resource = resourceFactory.newResource(file);
            assertTrue(resource.exists());
            assertFalse(resource.isAlias());
            resource = resourceFactory.newResource(file.toAbsolutePath());
            assertTrue(resource.exists());
            assertFalse(resource.isAlias());
            resource = resourceFactory.newResource(file.toUri());
            assertTrue(resource.exists());
            assertFalse(resource.isAlias());
            resource = resourceFactory.newResource(file.toUri().toString());
            assertTrue(resource.exists());
            assertFalse(resource.isAlias());
            resource = dir.resolve("test.txt");
            assertTrue(resource.exists());
            assertFalse(resource.isAlias());

            // Test alias paths
            resource = resourceFactory.newResource(file0);
            assertTrue(resource.exists());
            assertTrue(resource.isAlias());
            resource = resourceFactory.newResource(file0.toAbsolutePath());
            assertTrue(resource.exists());
            assertTrue(resource.isAlias());
            resource = resourceFactory.newResource(file0.toUri());
            assertTrue(resource.exists());
            assertTrue(resource.isAlias());
            resource = resourceFactory.newResource(file0.toUri().toString());
            assertTrue(resource.exists());
            assertTrue(resource.isAlias());

            resource = dir.resolve("test.txt\0");
            assertTrue(resource.exists());
            assertTrue(resource.isAlias());
        }
        catch (InvalidPathException e)
        {
            // this file system does allow null char ending filenames
            LOG.trace("IGNORED", e);
        }
    }
}
