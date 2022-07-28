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

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class ClassPathResourceTest
{
    @BeforeEach
    public void beforeEach()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @AfterEach
    public void afterEach()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    /**
     * Test a class path resource for existence.
     */
    @Test
    public void testClassPathResourceClassRelative()
    {
        final String classPathName = "Resource.class";

        Resource resource = Resource.newClassPathResource(classPathName);

        // A class path cannot be a directory
        assertFalse(resource.isDirectory(), "Class path cannot be a directory.");

        // A class path must exist
        assertTrue(resource.exists(), "Class path resource does not exist.");
    }

    /**
     * Test a class path resource for existence.
     */
    @Test
    public void testClassPathResourceClassAbsolute()
    {
        final String classPathName = "/org/eclipse/jetty/util/resource/Resource.class";

        Resource resource = Resource.newClassPathResource(classPathName);

        // A class path cannot be a directory
        assertFalse(resource.isDirectory(), "Class path cannot be a directory.");

        // A class path must exist
        assertTrue(resource.exists(), "Class path resource does not exist.");
    }

    /**
     * Test a class path resource for directories.
     *
     * @throws Exception failed test
     */
    @Test
    public void testClassPathResourceDirectory() throws Exception
    {
        // If the test runs in the module-path, resource "/" cannot be found.
        assumeFalse(Resource.class.getModule().isNamed());

        final String classPathName = "/";

        Resource resource = Resource.newClassPathResource(classPathName);

        // A class path must be a directory
        assertTrue(resource.isDirectory(), "Class path must be a directory.");

        assertTrue(Files.isDirectory(resource.getPath()), "Class path returned file must be a directory.");

        // A class path must exist
        assertTrue(resource.exists(), "Class path resource does not exist.");
    }

    /**
     * Test a class path resource for a file.
     *
     * @throws Exception failed test
     */
    @Test
    public void testClassPathResourceFile() throws Exception
    {
        final String fileName = "resource.txt";
        final String classPathName = "/" + fileName;

        // Will locate a resource in the class path
        Resource resource = Resource.newClassPathResource(classPathName);

        // A class path cannot be a directory
        assertFalse(resource.isDirectory(), "Class path must be a directory.");

        assertTrue(resource != null);

        Path path = resource.getPath();

        assertEquals(fileName, path.getFileName().toString(), "File name from class path is not equal.");
        assertTrue(Files.isRegularFile(path), "File returned from class path should be a regular file.");

        // A class path must exist
        assertTrue(resource.exists(), "Class path resource does not exist.");
    }
}
