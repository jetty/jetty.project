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
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class JarUrlResourceFactoryTest
{
    @Test
    public void testInvalidURL()
    {
        JarURLResourceFactory urlResourceFactory = new JarURLResourceFactory();
        assertThrows(IllegalArgumentException.class, () -> urlResourceFactory.newResource(new URL("http://localhost")));
    }

    @Test
    public void testJarFileUrl() throws Exception
    {
        Path path = MavenTestingUtils.getTestResourcePath("example.jar");
        URL jarFileUrl = new URL("jar:file:" + path.toAbsolutePath() + "!/WEB-INF/web.xml");
        int fileSize = (int)fileSize(jarFileUrl);
        JarURLResourceFactory urlResourceFactory = new JarURLResourceFactory();
        Resource resource = urlResourceFactory.newResource(jarFileUrl);

        assertThat(resource.isDirectory(), is(false));

        try (ReadableByteChannel channel = resource.newReadableByteChannel())
        {
            ByteBuffer buffer = ByteBuffer.allocate(fileSize);
            int read = channel.read(buffer);
            assertThat(read, is(fileSize));
        }
    }

    @Test
    public void testJarFileDirectoryUrl() throws Exception
    {
        Path path = MavenTestingUtils.getTestResourcePath("example.jar");
        URL jarFileUrl = new URL("jar:file://" + path.toAbsolutePath() + "!/WEB-INF");
        JarURLResourceFactory urlResourceFactory = new JarURLResourceFactory();
        Resource resource = urlResourceFactory.newResource(jarFileUrl);

        assertThat(resource.isDirectory(), is(true));

        List<Resource> subResources = resource.list();
        assertThat(subResources.size(), is(3));
        assertThat(subResources.get(0).getName(), is(jarFileUrl + "/classes/"));
        assertThat(subResources.get(1).getName(), is(jarFileUrl + "/lib/"));
        assertThat(subResources.get(2).getName(), is(jarFileUrl + "/web.xml"));
    }

    private static long fileSize(URL url) throws IOException
    {
        try (InputStream is = url.openStream())
        {
            long totalRead = 0;
            byte[] buffer = new byte[512];
            while (true)
            {
                int read = is.read(buffer);
                if (read == -1)
                    break;
                totalRead += read;
            }
            return totalRead;
        }
    }
}
