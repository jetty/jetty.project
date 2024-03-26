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

package org.eclipse.jetty.io;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class IOResourcesTest
{
    private ArrayByteBufferPool.Tracking bufferPool;

    @BeforeEach
    public void setUp()
    {
        bufferPool = new ArrayByteBufferPool.Tracking();
    }

    @AfterEach
    public void tearDown()
    {
        assertThat("Leaks: " + bufferPool.dumpLeaks(), bufferPool.getLeaks().size(), is(0));
    }

    @Test
    public void testToRetainableByteBuffer() throws Exception
    {
        Path resourcePath = MavenTestingUtils.getTestResourcePath("keystore.p12");
        Resource resource = ResourceFactory.root().newResource(resourcePath);
        RetainableByteBuffer retainableByteBuffer = IOResources.toRetainableByteBuffer(resource, bufferPool, false);
        assertThat(retainableByteBuffer.remaining(), is((int)Files.size(resourcePath)));
        retainableByteBuffer.release();
    }

    @Test
    public void testCopy() throws Exception
    {
        Path resourcePath = MavenTestingUtils.getTestResourcePath("keystore.p12");
        Resource resource = ResourceFactory.root().newResource(resourcePath);

        TestSink sink = new TestSink();
        Callback.Completable callback = new Callback.Completable();
        IOResources.copy(resource, sink, bufferPool, 1, false, callback);
        callback.get();
        List<Content.Chunk> chunks = sink.takeAccumulatedChunks();
        long sum = chunks.stream().mapToLong(Content.Chunk::remaining).sum();
        assertThat(sum, is(Files.size(resourcePath)));
        assertThat(chunks.get(chunks.size() - 1).isLast(), is(true));
    }

    @Test
    public void testCopyWithFirst() throws Exception
    {
        Path resourcePath = MavenTestingUtils.getTestResourcePath("keystore.p12");
        Resource resource = ResourceFactory.root().newResource(resourcePath);

        TestSink sink = new TestSink();
        Callback.Completable callback = new Callback.Completable();
        IOResources.copy(resource, sink, bufferPool, 1, false, 100, -1, callback);
        callback.get();
        List<Content.Chunk> chunks = sink.takeAccumulatedChunks();
        long sum = chunks.stream().mapToLong(Content.Chunk::remaining).sum();
        assertThat(sum, is(Files.size(resourcePath) - 100L));
        assertThat(chunks.get(chunks.size() - 1).isLast(), is(true));
    }

    @Test
    public void testCopyWithLength() throws Exception
    {
        Path resourcePath = MavenTestingUtils.getTestResourcePath("keystore.p12");
        Resource resource = ResourceFactory.root().newResource(resourcePath);

        TestSink sink = new TestSink();
        Callback.Completable callback = new Callback.Completable();
        IOResources.copy(resource, sink, bufferPool, 1, false, -1, 500, callback);
        callback.get();
        List<Content.Chunk> chunks = sink.takeAccumulatedChunks();
        long sum = chunks.stream().mapToLong(Content.Chunk::remaining).sum();
        assertThat(sum, is(500L));
        assertThat(chunks.get(chunks.size() - 1).isLast(), is(true));
    }

    @Test
    public void testCopyWithFirstAndLength() throws Exception
    {
        Path resourcePath = MavenTestingUtils.getTestResourcePath("keystore.p12");
        Resource resource = ResourceFactory.root().newResource(resourcePath);

        TestSink sink = new TestSink();
        Callback.Completable callback = new Callback.Completable();
        IOResources.copy(resource, sink, bufferPool, 1, false, 100, 500, callback);
        callback.get();
        List<Content.Chunk> chunks = sink.takeAccumulatedChunks();
        long sum = chunks.stream().mapToLong(Content.Chunk::remaining).sum();
        assertThat(sum, is(500L));
        assertThat(chunks.get(chunks.size() - 1).isLast(), is(true));
    }
}
