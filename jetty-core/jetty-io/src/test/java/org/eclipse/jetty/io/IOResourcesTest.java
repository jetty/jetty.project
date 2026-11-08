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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.URLResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

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
        public Content.Source newContentSource(ByteBufferPool.Sized bufferPool, long offset, long length)
        {
            length = TypeUtil.checkOffsetLengthSize(offset, length, buffer.remaining());
            return Content.Source.from(BufferUtil.slice(buffer, Math.toIntExact(offset), Math.toIntExact(length)));
        }
    }

    // This Resource impl has getPath() return null so newInputStream() is the only way to read its contents,
    // plus the returned input stream can only read 1 byte at a time.
    private static class TestInputStreamResource extends Resource
    {
        private final URI uri;
        private final byte[] buffer;

        public TestInputStreamResource(URI uri, byte[] buffer)
        {
            this.uri = uri;
            this.buffer = buffer;
        }

        @Override
        public boolean exists()
        {
            return true;
        }

        @Override
        public long length()
        {
            return buffer.length;
        }

        @Override
        public Path getPath()
        {
            return null;
        }

        @Override
        public InputStream newInputStream()
        {
            return new ByteArrayInputStream(buffer)
            {
                @Override
                public synchronized int read(byte[] b, int off, int len)
                {
                    int read = read();
                    if (read == -1)
                        return -1;
                    b[off] = (byte)read;
                    return 1;
                }
            };
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
    }

    public static Stream<Arguments> resourcesWithBytes() throws Exception
    {
        Path testResourcePath = MavenTestingUtils.getTestResourcePath("keystore.p12");
        URI testResourceUri = testResourcePath.toUri();
        byte[] testResourceBytes = Files.readAllBytes(testResourcePath);
        return Stream.of(
            arguments(ResourceFactory.root().newResource(testResourceUri), testResourceBytes),
            arguments(ResourceFactory.root().newResource(testResourceUri), testResourceBytes),
            arguments(ResourceFactory.root().newMemoryResource(testResourceUri.toURL()), testResourceBytes),
            arguments(ResourceFactory.root().newResource(MavenTestingUtils.getTestResourcePath("zero")), BufferUtil.EMPTY_BYTES),
            arguments(ResourceFactory.root().newResource(MavenTestingUtils.getTestResourcePath("one")), Files.readAllBytes(MavenTestingUtils.getTestResourcePath("one"))),
            arguments(new URLResourceFactory().newResource(testResourceUri), testResourceBytes),
            arguments(new TestInputStreamResource(testResourceUri, testResourceBytes), testResourceBytes),
            arguments(new TestContentSourceFactoryResource(testResourceUri, testResourceBytes), testResourceBytes)
        );
    }

    public static Stream<Resource> resources() throws Exception
    {
        return resourcesWithBytes().map(arguments -> (Resource)arguments.get()[0]);
    }

    @ParameterizedTest
    @MethodSource("resourcesWithBytes")
    public void testToRetainableByteBuffer(Resource resource, byte[] resourceBytes)
    {
        RetainableByteBuffer retainableByteBuffer = IOResources.toRetainableByteBuffer(resource, bufferPool);
        assertThat(retainableByteBuffer.remaining(), is((int)resource.length()));
        assertThat(retainableByteBuffer.size(), is(resource.length()));
        assertArrayEquals(resourceBytes, BufferUtil.toArray(retainableByteBuffer.getByteBuffer()));
        retainableByteBuffer.release();
    }

    @ParameterizedTest
    @MethodSource("resources")
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
    @MethodSource("resources")
    public void testAsContentSourceWithOffset(Resource resource) throws Exception
    {
        TestSink sink = new TestSink();
        Callback.Completable callback = new Callback.Completable();

        if (resource.length() >= 0 && resource.length() < 100)
        {
            assertThrows(IndexOutOfBoundsException.class, () -> IOResources.asContentSource(resource, bufferPool, 100, -1));
            return;
        }
        Content.Source contentSource = IOResources.asContentSource(resource, bufferPool, 100, -1);
        Content.copy(contentSource, sink, callback);
        callback.get();
        List<Content.Chunk> chunks = sink.takeAccumulatedChunks();
        long sum = chunks.stream().mapToLong(Content.Chunk::remaining).sum();
        assertThat(sum, is(Math.max(0L, resource.length() - 100L)));
        assertThat(chunks.get(chunks.size() - 1).isLast(), is(true));
    }

    @ParameterizedTest
    @MethodSource("resources")
    public void testAsContentSourceWithLength(Resource resource) throws Exception
    {
        TestSink sink = new TestSink();
        Callback.Completable callback = new Callback.Completable();
        Content.Source contentSource = IOResources.asContentSource(resource, bufferPool, 0, 500);
        Content.copy(contentSource, sink, callback);
        callback.get();
        List<Content.Chunk> chunks = sink.takeAccumulatedChunks();
        long sum = chunks.stream().mapToLong(Content.Chunk::remaining).sum();
        assertThat(sum, is(Math.min(resource.length(), 500L)));
        assertThat(chunks.get(chunks.size() - 1).isLast(), is(true));
    }

    @ParameterizedTest
    @MethodSource("resources")
    public void testAsContentSourceWithOffsetAndLength(Resource resource) throws Exception
    {
        TestSink sink = new TestSink();
        Callback.Completable callback = new Callback.Completable();

        long offset = Math.min(resource.length(), 100);
        long length = Math.min(resource.length() - offset, 500);
        Content.Source contentSource = IOResources.asContentSource(resource, bufferPool, offset, length);
        Content.copy(contentSource, sink, callback);
        callback.get();
        List<Content.Chunk> chunks = sink.takeAccumulatedChunks();
        long sum = chunks.stream().mapToLong(Content.Chunk::remaining).sum();
        assertThat(sum, is(length));
        assertThat(chunks.get(chunks.size() - 1).isLast(), is(true));
    }

    @ParameterizedTest
    @MethodSource("resources")
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
    @MethodSource("resources")
    public void testCopyWithOffset(Resource resource) throws Exception
    {
        TestSink sink = new TestSink();
        Callback.Completable callback = new Callback.Completable();
        long offset = Math.min(resource.length(), 100);
        IOResources.copy(resource, sink, bufferPool, offset, -1, callback);
        callback.get();
        List<Content.Chunk> chunks = sink.takeAccumulatedChunks();
        long sum = chunks.stream().mapToLong(Content.Chunk::remaining).sum();
        assertThat(sum, is(Math.max(0L, resource.length() - 100L)));
        assertThat(chunks.get(chunks.size() - 1).isLast(), is(true));
    }

    @ParameterizedTest
    @MethodSource("resources")
    public void testCopyWithLength(Resource resource) throws Exception
    {
        TestSink sink = new TestSink();
        Callback.Completable callback = new Callback.Completable();
        long length = resource.length() >= 0 ? Math.min(resource.length(), 500) : 500;
        IOResources.copy(resource, sink, bufferPool, 0, length, callback);
        callback.get();
        List<Content.Chunk> chunks = sink.takeAccumulatedChunks();
        long sum = chunks.stream().mapToLong(Content.Chunk::remaining).sum();
        assertThat(sum, is(length));
        assertThat(chunks.get(chunks.size() - 1).isLast(), is(true));
    }

    @ParameterizedTest
    @MethodSource("resources")
    public void testCopyWithOffsetAndLength(Resource resource) throws Exception
    {
        TestSink sink = new TestSink();
        Callback.Completable callback = new Callback.Completable();
        long offset = Math.min(resource.length(), 100);
        long length = Math.min(resource.length() - offset, 500);
        IOResources.copy(resource, sink, bufferPool, offset, length, callback);
        callback.get();
        List<Content.Chunk> chunks = sink.takeAccumulatedChunks();
        long sum = chunks.stream().mapToLong(Content.Chunk::remaining).sum();
        assertThat(sum, is(length));
        assertThat(chunks.get(chunks.size() - 1).isLast(), is(true));
    }

    @ParameterizedTest
    @MethodSource("resources")
    public void testOutOfRangeOffset(Resource resource)
    {
        TestSink sink = new TestSink();
        Blocker.Callback callback = Blocker.callback();
        IOResources.copy(resource, sink, bufferPool, Integer.MAX_VALUE, 1, callback);
        assertThrows(IndexOutOfBoundsException.class, callback::block);
    }

    @ParameterizedTest
    @MethodSource("resources")
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

    @Test
    public void testCopyDirectory()
    {
        Resource resource = ResourceFactory.root().newResource(MavenTestingUtils.getTestResourcesPath());
        TestSink sink = new TestSink();
        Callback.Completable callback = new Callback.Completable();
        IOResources.copy(resource, sink, bufferPool, 0, -1, callback);
        Throwable cause = assertThrows(ExecutionException.class, callback::get).getCause();
        assertThat(cause, instanceOf(IllegalArgumentException.class));
        assertThat(sink.takeAccumulatedChunks(), empty());
    }
}
