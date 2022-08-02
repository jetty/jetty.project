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
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class ResourceAliasTest
{
    private static final Logger LOG = LoggerFactory.getLogger(ResourceAliasTest.class);

    public WorkDir workDir;

    @Test
    public void testPercentPaths() throws IOException
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

        Resource baseResource = Resource.newResource(baseDir);
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
    public void testNullCharEndingFilename() throws Exception
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

            Resource dir = Resource.newResource(baseDir);

            // Test not alias paths
            Resource resource = Resource.newResource(file);
            assertTrue(resource.exists());
            assertNull(resource.getAlias());
            resource = Resource.newResource(file.toAbsolutePath());
            assertTrue(resource.exists());
            assertNull(resource.getAlias());
            resource = Resource.newResource(file.toUri());
            assertTrue(resource.exists());
            assertNull(resource.getAlias());
            resource = Resource.newResource(file.toUri().toString());
            assertTrue(resource.exists());
            assertNull(resource.getAlias());
            resource = dir.resolve("test.txt");
            assertTrue(resource.exists());
            assertNull(resource.getAlias());

            // Test alias paths
            resource = Resource.newResource(file0);
            assertTrue(resource.exists());
            assertNotNull(resource.getAlias());
            resource = Resource.newResource(file0.toAbsolutePath());
            assertTrue(resource.exists());
            assertNotNull(resource.getAlias());
            resource = Resource.newResource(file0.toUri());
            assertTrue(resource.exists());
            assertNotNull(resource.getAlias());
            resource = Resource.newResource(file0.toUri().toString());
            assertTrue(resource.exists());
            assertNotNull(resource.getAlias());

            resource = dir.resolve("test.txt\0");
            assertTrue(resource.exists());
            assertNotNull(resource.getAlias());
        }
        catch (InvalidPathException e)
        {
            // this file system does allow null char ending filenames
            LOG.trace("IGNORED", e);
        }
    }
}
