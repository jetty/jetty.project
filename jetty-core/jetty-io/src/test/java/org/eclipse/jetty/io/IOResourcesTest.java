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

import java.net.URI;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.URLResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class IOResourcesTest
{
    private ArrayByteBufferPool.Tracking trackingPool;
    private ByteBufferPool.Sized bufferPool;

    @BeforeEach
    public void setUp()
    {
        trackingPool = new ArrayByteBufferPool.Tracking();
        bufferPool = new ByteBufferPool.Sized(trackingPool, false, 1);
    }

    @AfterEach
    public void tearDown()
    {
        assertThat("Leaks: " + trackingPool.dumpLeaks(), trackingPool.getLeaks().size(), is(0));
    }

    public static Stream<Resource> all() throws Exception
    {
        URI resourceUri = MavenTestingUtils.getTestResourcePath("keystore.p12").toUri();
        return Stream.of(
            ResourceFactory.root().newResource(resourceUri),
            ResourceFactory.root().newMemoryResource(resourceUri.toURL()),
            new URLResourceFactory().newResource(resourceUri)
        );
    }

    @ParameterizedTest
    @MethodSource("all")
    public void testToRetainableByteBuffer(Resource resource)
    {
        RetainableByteBuffer retainableByteBuffer = IOResources.toRetainableByteBuffer(resource, bufferPool);
        assertThat(retainableByteBuffer.remaining(), is((int)resource.length()));
        retainableByteBuffer.release();
    }

    @ParameterizedTest
    @MethodSource("all")
    public void testAsContentSource(Resource resource) throws Exception
    {
        TestSink sink = new TestSink();
        Callback.Completable callback = new Callback.Completable();
        Content.Source contentSource = IOResources.asContentSource(resource, bufferPool, 0L, -1L);
        Content.copy(contentSource, sink, callback);
        callback.get();
        List<Content.Chunk> chunks = sink.takeAccumulatedChunks();
        long sum = chunks.stream().mapToLong(Content.Chunk::remaining).sum();
        assertThat(sum, is(resource.length()));
        assertThat(chunks.get(chunks.size() - 1).isLast(), is(true));
    }

    @ParameterizedTest
    @MethodSource("all")
    public void testAsContentSourceWithFirst(Resource resource) throws Exception
    {
        TestSink sink = new TestSink();
        Callback.Completable callback = new Callback.Completable();
        Content.Source contentSource = IOResources.asContentSource(resource, bufferPool, 100, -1);
        Content.copy(contentSource, sink, callback);
        callback.get();
        List<Content.Chunk> chunks = sink.takeAccumulatedChunks();
        long sum = chunks.stream().mapToLong(Content.Chunk::remaining).sum();
        assertThat(sum, is(resource.length() - 100L));
        assertThat(chunks.get(chunks.size() - 1).isLast(), is(true));
    }

    @ParameterizedTest
    @MethodSource("all")
    public void testAsContentSourceWithLength(Resource resource) throws Exception
    {
        TestSink sink = new TestSink();
        Callback.Completable callback = new Callback.Completable();
        Content.Source contentSource = IOResources.asContentSource(resource, bufferPool, -1, 500);
        Content.copy(contentSource, sink, callback);
        callback.get();
        List<Content.Chunk> chunks = sink.takeAccumulatedChunks();
        long sum = chunks.stream().mapToLong(Content.Chunk::remaining).sum();
        assertThat(sum, is(500L));
        assertThat(chunks.get(chunks.size() - 1).isLast(), is(true));
    }

    @ParameterizedTest
    @MethodSource("all")
    public void testAsContentSourceWithFirstAndLength(Resource resource) throws Exception
    {
        TestSink sink = new TestSink();
        Callback.Completable callback = new Callback.Completable();
        Content.Source contentSource = IOResources.asContentSource(resource, bufferPool, 100, 500);
        Content.copy(contentSource, sink, callback);
        callback.get();
        List<Content.Chunk> chunks = sink.takeAccumulatedChunks();
        long sum = chunks.stream().mapToLong(Content.Chunk::remaining).sum();
        assertThat(sum, is(500L));
        assertThat(chunks.get(chunks.size() - 1).isLast(), is(true));
    }

    @ParameterizedTest
    @MethodSource("all")
    public void testCopy(Resource resource) throws Exception
    {
        TestSink sink = new TestSink();
        Callback.Completable callback = new Callback.Completable();
        IOResources.copy(resource, sink, bufferPool, 0L, -1L, callback);
        callback.get();
        List<Content.Chunk> chunks = sink.takeAccumulatedChunks();
        long sum = chunks.stream().mapToLong(Content.Chunk::remaining).sum();
        assertThat(sum, is(resource.length()));
        assertThat(chunks.get(chunks.size() - 1).isLast(), is(true));
    }

    @ParameterizedTest
    @MethodSource("all")
    public void testCopyWithFirst(Resource resource) throws Exception
    {
        TestSink sink = new TestSink();
        Callback.Completable callback = new Callback.Completable();
        IOResources.copy(resource, sink, bufferPool, 100, -1, callback);
        callback.get();
        List<Content.Chunk> chunks = sink.takeAccumulatedChunks();
        long sum = chunks.stream().mapToLong(Content.Chunk::remaining).sum();
        assertThat(sum, is(resource.length() - 100L));
        assertThat(chunks.get(chunks.size() - 1).isLast(), is(true));
    }

    @ParameterizedTest
    @MethodSource("all")
    public void testCopyWithLength(Resource resource) throws Exception
    {
        TestSink sink = new TestSink();
        Callback.Completable callback = new Callback.Completable();
        IOResources.copy(resource, sink, bufferPool, -1, 500, callback);
        callback.get();
        List<Content.Chunk> chunks = sink.takeAccumulatedChunks();
        long sum = chunks.stream().mapToLong(Content.Chunk::remaining).sum();
        assertThat(sum, is(500L));
        assertThat(chunks.get(chunks.size() - 1).isLast(), is(true));
    }

    @ParameterizedTest
    @MethodSource("all")
    public void testCopyWithFirstAndLength(Resource resource) throws Exception
    {
        TestSink sink = new TestSink();
        Callback.Completable callback = new Callback.Completable();
        IOResources.copy(resource, sink, bufferPool, 100, 500, callback);
        callback.get();
        List<Content.Chunk> chunks = sink.takeAccumulatedChunks();
        long sum = chunks.stream().mapToLong(Content.Chunk::remaining).sum();
        assertThat(sum, is(500L));
        assertThat(chunks.get(chunks.size() - 1).isLast(), is(true));
    }

    @ParameterizedTest
    @MethodSource("all")
    public void testOutOfRangeOffset(Resource resource)
    {
        TestSink sink = new TestSink();
        Blocker.Callback callback = Blocker.callback();
        IOResources.copy(resource, sink, bufferPool, Integer.MAX_VALUE, 1, callback);
        assertThrows(IllegalArgumentException.class, callback::block);
    }

    @ParameterizedTest
    @MethodSource("all")
    public void testOutOfRangeOffsetWithZeroLength(Resource resource) throws Exception
    {
        TestSink sink = new TestSink();
        Callback.Completable callback = new Callback.Completable();
        IOResources.copy(resource, sink, bufferPool, Integer.MAX_VALUE, 0, callback);
        callback.get();
        List<Content.Chunk> chunks = sink.takeAccumulatedChunks();
        long sum = chunks.stream().mapToLong(Content.Chunk::remaining).sum();
        assertThat(sum, is(0L));
        assertThat(chunks.get(chunks.size() - 1).isLast(), is(true));
    }
}
