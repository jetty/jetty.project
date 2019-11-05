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

package org.eclipse.jetty.util.resource;

import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class ResourceAliasTest
{
    public WorkDir workDir;

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
                return;  // this file system does not suffer this problem

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
            resource = dir.addPath("test.txt");
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

            try
            {
                resource = dir.addPath("test.txt\0");
                assertTrue(resource.exists());
                assertNotNull(resource.getAlias());
            }
            catch (MalformedURLException e)
            {
                assertTrue(true);
            }
        }
        catch (InvalidPathException e)
        {
            // this file system does not suffer this problem
        }
    }
}
