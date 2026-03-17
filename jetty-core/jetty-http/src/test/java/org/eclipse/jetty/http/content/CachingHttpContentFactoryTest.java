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

package org.eclipse.jetty.http.content;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.IOResources;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.resource.MemoryResource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@ExtendWith(WorkDirExtension.class)
public class CachingHttpContentFactoryTest
{
    public WorkDir workDir;
    private ArrayByteBufferPool.Tracking trackingPool;
    private ByteBufferPool.Sized sizedPool;

    @BeforeEach
    public void setUp()
    {
        trackingPool = new ArrayByteBufferPool.Tracking();
        sizedPool = new ByteBufferPool.Sized(trackingPool);
    }

    @AfterEach
    public void tearDown()
    {
        assertThat("Leaks: " + trackingPool.dumpLeaks(), trackingPool.getLeaks().size(), is(0));
    }

    @Test
    public void testUnknownLengthIsNotCached() throws Exception
    {
        HttpContent.Factory authority = path -> new HttpContent.Wrapper(new ResourceHttpContent(new MemoryResource(URI.create("file://test"), new byte[] {'a', 'b', 'c'}), "text/html", sizedPool))
        {
            @Override
            public long getContentLengthValue()
            {
                return -1;
            }
        };

        CachingHttpContentFactory cachingHttpContentFactory = new CachingHttpContentFactory(authority, sizedPool);

        HttpContent httpContent = cachingHttpContentFactory.getContent("path");
        assertThat(cachingHttpContentFactory.getCachedFiles(), is(0));
        assertThat(httpContent.getResource().getURI(), is(URI.create("file://test")));
        RetainableByteBuffer rbb = IOResources.toRetainableByteBuffer(httpContent.getResource(), sizedPool);
        try
        {
            ByteBuffer byteBuffer = rbb.getByteBuffer();
            assertThat(byteBuffer.remaining(), is(3));
            assertThat(byteBuffer.get(), is((byte)'a'));
            assertThat(byteBuffer.get(), is((byte)'b'));
            assertThat(byteBuffer.get(), is((byte)'c'));
        }
        finally
        {
            rbb.release();
        }
    }

    @Test
    public void testWriteEvictedContent() throws Exception
    {
        Path file = Files.writeString(workDir.getEmptyPathDir().resolve("file.txt"), "0123456789abcdefghijABCDEFGHIJ");
        ResourceHttpContentFactory resourceHttpContentFactory = new ResourceHttpContentFactory(ResourceFactory.root().newResource(file.getParent()), MimeTypes.DEFAULTS, sizedPool);
        CachingHttpContentFactory cachingHttpContentFactory = new CachingHttpContentFactory(resourceHttpContentFactory, sizedPool);

        HttpContent content = cachingHttpContentFactory.getContent("file.txt");
        assertThat(cachingHttpContentFactory.getCachedFiles(), is(1));

        // Empty the cache so 'content' gets released.
        cachingHttpContentFactory.flushCache();
        assertThat(cachingHttpContentFactory.getCachedFiles(), is(0));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (Blocker.Callback cb = Blocker.callback())
        {
            content.writeTo(Content.Sink.from(baos), 0L, -1L, cb);
            cb.block();
        }
        assertThat(baos.toString(StandardCharsets.UTF_8), is("0123456789abcdefghijABCDEFGHIJ"));
    }

    @Test
    public void testEvictBetweenWriteToAndSinkWrite() throws Exception
    {
        Path file = Files.writeString(workDir.getEmptyPathDir().resolve("file.txt"), "0123456789abcdefghijABCDEFGHIJ");
        ResourceHttpContentFactory resourceHttpContentFactory = new ResourceHttpContentFactory(ResourceFactory.root().newResource(file.getParent()), MimeTypes.DEFAULTS, sizedPool);
        CachingHttpContentFactory cachingHttpContentFactory = new CachingHttpContentFactory(resourceHttpContentFactory, sizedPool);

        HttpContent content = cachingHttpContentFactory.getContent("file.txt");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (Blocker.Callback cb = Blocker.callback())
        {
            content.writeTo((last, byteBuffer, callback) ->
            {
                // Empty the cache so 'content' gets released.
                cachingHttpContentFactory.flushCache();

                Content.Sink.from(baos).write(last, byteBuffer, callback);
            }, 0L, -1L, cb);
            cb.block();
        }
        assertThat(baos.toString(StandardCharsets.UTF_8), is("0123456789abcdefghijABCDEFGHIJ"));
    }
}
