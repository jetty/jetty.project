//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Test;

public class ClassPathResourceTest
{
    /**
     * Test a class path resource for existence.
     */
    @Test
    public void testClassPathResourceClassRelative()
    {
        final String classPathName="Resource.class";

        try(Resource resource=Resource.newClassPathResource(classPathName);)
        {
            // A class path cannot be a directory
            assertFalse("Class path cannot be a directory.",resource.isDirectory());

            // A class path must exist
            assertTrue("Class path resource does not exist.",resource.exists());
        }
    }

    /**
     * Test a class path resource for existence.
     */
    @Test
    public void testClassPathResourceClassAbsolute()
    {
        final String classPathName="/org/eclipse/jetty/util/resource/Resource.class";

        Resource resource=Resource.newClassPathResource(classPathName);

        // A class path cannot be a directory
        assertFalse("Class path cannot be a directory.",resource.isDirectory());

        // A class path must exist
        assertTrue("Class path resource does not exist.",resource.exists());
    }

    /**
     * Test a class path resource for directories.
     * @throws Exception failed test
     */
    @Test
    public void testClassPathResourceDirectory() throws Exception
    {
        final String classPathName="/";

        Resource resource=Resource.newClassPathResource(classPathName);

        // A class path must be a directory
        assertTrue("Class path must be a directory.",resource.isDirectory());

        assertTrue("Class path returned file must be a directory.",resource.getFile().isDirectory());

        // A class path must exist
        assertTrue("Class path resource does not exist.",resource.exists());
    }

    /**
     * Test a class path resource for a file.
     * @throws Exception failed test
     */
    @Test
    public void testClassPathResourceFile() throws Exception
    {
        final String fileName="resource.txt";
        final String classPathName="/"+fileName;

        // Will locate a resource in the class path
        Resource resource=Resource.newClassPathResource(classPathName);

        // A class path cannot be a directory
        assertFalse("Class path must be a directory.",resource.isDirectory());

        assertTrue(resource!=null);

        File file=resource.getFile();

        assertEquals("File name from class path is not equal.",fileName,file.getName());
        assertTrue("File returned from class path should be a file.",file.isFile());

        // A class path must exist
        assertTrue("Class path resource does not exist.",resource.exists());
    }
}
