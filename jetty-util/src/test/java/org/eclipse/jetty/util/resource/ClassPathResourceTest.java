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

package org.eclipse.jetty.util.resource;

import java.io.File;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClassPathResourceTest
{
    /**
     * Test a class path resource for existence.
     */
    @Test
    public void testClassPathResourceClassRelative()
    {
        final String classPathName = "Resource.class";

        try (Resource resource = Resource.newClassPathResource(classPathName);)
        {
            // A class path cannot be a directory
            assertFalse(resource.isDirectory(), "Class path cannot be a directory.");

            // A class path must exist
            assertTrue(resource.exists(), "Class path resource does not exist.");
        }
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
        final String classPathName = "/";

        Resource resource = Resource.newClassPathResource(classPathName);

        // A class path must be a directory
        assertTrue(resource.isDirectory(), "Class path must be a directory.");

        assertTrue(resource.getFile().isDirectory(), "Class path returned file must be a directory.");

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

        File file = resource.getFile();

        assertEquals(fileName, file.getName(), "File name from class path is not equal.");
        assertTrue(file.isFile(), "File returned from class path should be a file.");

        // A class path must exist
        assertTrue(resource.exists(), "Class path resource does not exist.");
    }
}
