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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Loader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WorkDirExtension.class)
public class MemoryResourceTest
{
    public WorkDir workDir;

    @Test
    public void testJettyLogging() throws Exception
    {
        Resource resource = ResourceFactory.root().newMemoryResource(Loader.getResource("jetty-logging.properties"));
        assertTrue(resource.exists());
        try (InputStream in = resource.newInputStream())
        {
            String contents = IO.toString(in);
            assertThat(contents, startsWith("#org.eclipse.jetty.util.LEVEL=DEBUG"));
        }
    }

    @Test
    public void testCopyToFile() throws Exception
    {
        Resource resource = ResourceFactory.root().newMemoryResource(Loader.getResource("jetty-logging.properties"));
        Path targetFile = workDir.getEmptyPathDir().resolve("target-jetty-logging.properties");
        resource.copyTo(targetFile);

        assertThat(Files.exists(targetFile), is(true));
    }

    @Test
    public void testCopyToFolder() throws Exception
    {
        Resource resource = ResourceFactory.root().newMemoryResource(Loader.getResource("jetty-logging.properties"));
        Path targetDir = workDir.getEmptyPathDir();
        resource.copyTo(targetDir);

        Path resolved = targetDir.resolve("jetty-logging.properties");
        assertThat(Files.exists(resolved), is(true));
    }
}
