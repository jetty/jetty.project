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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UrlResourceFactoryTest
{
    @Test
    @Tag("external")
    public void testHttps() throws IOException
    {
        ResourceFactory.registerResourceFactory("https", new URLResourceFactory());
        Resource resource = ResourceFactory.root().newResource(URI.create("https://webtide.com/"));
        assertThat(resource, notNullValue());
        assertTrue(resource.exists());

        try (InputStream in = resource.newInputStream())
        {
            String result = IO.toString(in, StandardCharsets.UTF_8);
            assertThat(result, containsString("webtide.com"));
        }

        assertThat(resource.lastModified().toEpochMilli(), not(Instant.EPOCH));
        assertThat(resource.length(), not(-1));
        assertTrue(resource.isDirectory());
        assertThat(resource.getFileName(), is(""));

        Resource blogs = resource.resolve("blogs/");
        assertThat(blogs, notNullValue());
        assertTrue(blogs.exists());
        assertThat(blogs.lastModified().toEpochMilli(), not(Instant.EPOCH));
        assertThat(blogs.length(), not(-1));
        assertTrue(blogs.isDirectory());
        assertThat(blogs.getFileName(), is(""));

        Resource favicon = resource.resolve("favicon.ico");
        assertThat(favicon, notNullValue());
        assertTrue(favicon.exists());
        assertThat(favicon.lastModified().toEpochMilli(), not(Instant.EPOCH));
        assertThat(favicon.length(), not(-1));
        assertFalse(favicon.isDirectory());
        assertThat(favicon.getFileName(), is("favicon.ico"));
    }
}
