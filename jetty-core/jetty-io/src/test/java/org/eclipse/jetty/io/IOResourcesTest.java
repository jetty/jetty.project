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

import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.URLResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    // This Resource impl has getPath() and newInputStream() throw so the only way for IOResources
    // to read its contents is to call newContentSource().
    private static class TestContentSourceFactoryResource extends Resource implements Content.Source.Factory
    {
        private final URI uri;
        private final ByteBuffer buffer;

        public TestContentSourceFactoryResource(URI uri, byte[] bytes)
        {
            this.uri = uri;
            this.buffer = ByteBuffer.wrap(bytes);
        }

        @Override
        public boolean exists()
        {
            return true;
        }

        @Override
        public long length()
        {
            return buffer.remaining();
        }

        @Override
        public Path getPath()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputStream newInputStream()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isDirectory()
        {
            return false;
        }

        @Override
        public boolean isReadable()
        {
            return true;
        }

        @Override
        public URI getURI()
        {
            return uri;
        }

        @Override
        public String getName()
        {
            return uri.getPath();
        }

        @Override
        public String getFileName()
        {
            return uri.getPath();
        }

        @Override
        public Resource resolve(String subUriPath)
        {
            return null;
        }

        @Override
        public Content.Source newContentSource(ByteBufferPool.Sized bufferPool, long first, long length)
        {
            return Content.Source.from(BufferUtil.slice(buffer, Math.toIntExact(first), Math.toIntExact(length)));
        }
    }

    public static Stream<Resource> all() throws Exception
    {
        Path testResourcePath = MavenTestingUtils.getTestResourcePath("keystore.p12");
        URI resourceUri = testResourcePath.toUri();
        return Stream.of(
            ResourceFactory.root().newResource(resourceUri),
            ResourceFactory.root().newMemoryResource(resourceUri.toURL()),
            new URLResourceFactory().newResource(resourceUri),
            new TestContentSourceFactoryResource(resourceUri, Files.readAllBytes(testResourcePath))
        );
    }

    @ParameterizedTest
    @MethodSource("all")
    public void testToRetainableByteBuffer(Resource resource)
    {
        RetainableByteBuffer retainableByteBuffer = IOResources.toRetainableByteBuffer(resource, bufferPool, false);
        assertThat(retainableByteBuffer.remaining(), is((int)resource.length()));
        retainableByteBuffer.release();
    }

    @ParameterizedTest
    @MethodSource("all")
    public void testAsContentSource(Resource resource) throws Exception
    {
        TestSink sink = new TestSink();
        Callback.Completable callback = new Callback.Completable();
        Content.Source contentSource = IOResources.asContentSource(resource, bufferPool, 1, false);
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
        Content.Source contentSource = IOResources.asContentSource(resource, bufferPool, 1, false, 100, -1);
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
        Content.Source contentSource = IOResources.asContentSource(resource, bufferPool, 1, false, 0, 500);
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
        Content.Source contentSource = IOResources.asContentSource(resource, bufferPool, 1, false, 100, 500);
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
        IOResources.copy(resource, sink, bufferPool, 1, false, callback);
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
        IOResources.copy(resource, sink, bufferPool, 1, false, 100, -1, callback);
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
        IOResources.copy(resource, sink, bufferPool, 1, false, 0, 500, callback);
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
        IOResources.copy(resource, sink, bufferPool, 1, false, 100, 500, callback);
        callback.get();
        List<Content.Chunk> chunks = sink.takeAccumulatedChunks();
        long sum = chunks.stream().mapToLong(Content.Chunk::remaining).sum();
        assertThat(sum, is(500L));
        assertThat(chunks.get(chunks.size() - 1).isLast(), is(true));
    }

    @Test
    public void testCopyDirectory()
    {
        Resource resource = ResourceFactory.root().newResource(MavenTestingUtils.getTestResourcesPath());
        TestSink sink = new TestSink();
        Callback.Completable callback = new Callback.Completable();
        IOResources.copy(resource, sink, bufferPool, 1, false, callback);
        Throwable cause = assertThrows(ExecutionException.class, callback::get).getCause();
        assertThat(cause, instanceOf(IllegalArgumentException.class));
        assertThat(sink.takeAccumulatedChunks(), empty());
    }

    @Test
    public void testCopyWithRangeDirectory()
    {
        Resource resource = ResourceFactory.root().newResource(MavenTestingUtils.getTestResourcesPath());
        TestSink sink = new TestSink();
        Callback.Completable callback = new Callback.Completable();
        IOResources.copy(resource, sink, bufferPool, 1, false, 0, -1, callback);
        Throwable cause = assertThrows(ExecutionException.class, callback::get).getCause();
        assertThat(cause, instanceOf(IllegalArgumentException.class));
        assertThat(sink.takeAccumulatedChunks(), empty());
    }
}
