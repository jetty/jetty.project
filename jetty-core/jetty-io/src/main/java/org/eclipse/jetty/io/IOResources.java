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

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.function.Predicate;

import org.eclipse.jetty.io.content.InputStreamContentSource;
import org.eclipse.jetty.io.content.PathContentSource;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.resource.Resource;

public class IOResources
{
    public static Content.Source asContentSource(Resource resource)
    {
        return asContentSource(resource, null, 0, false);
    }

    public static Content.Source asContentSource(Resource resource, ByteBufferPool bufferPool, int bufferSize, boolean direct)
    {
        if (resource.isDirectory() || !resource.exists())
            throw new IllegalArgumentException("Resource cannot be a content source: " + resource);

        Path path = resource.getPath();
        if (path != null)
        {
            PathContentSource pathContentSource = new PathContentSource(path, bufferPool);
            if (bufferSize > 0)
            {
                pathContentSource.setBufferSize(bufferSize);
                pathContentSource.setUseDirectByteBuffers(direct);
            }
            return pathContentSource;
        }

        try
        {
            return new InputStreamContentSource(resource.newInputStream());
        }
        catch (IOException e)
        {
            throw new RuntimeIOException(e);
        }
    }

    public static InputStream asInputStream(Resource resource)
    {
        return Content.Source.asInputStream(asContentSource(resource));
    }

    public static void copy(Resource resource, ByteBufferPool bufferPool, int bufferSize, boolean direct, Content.Sink sink, Callback callback)
    {
        copy(asContentSource(resource, bufferPool, bufferSize, direct), sink, callback);
    }

    public static void copy(Content.Source source, Content.Sink sink, Callback callback)
    {
        copy(source, sink, x -> false, callback);
    }

    public static void copy(Content.Source source, Content.Sink sink, Predicate<Throwable> onTransientError, Callback callback)
    {
        new ContentCopierIteratingCallback(source, sink, onTransientError, callback).iterate();
    }

    private static class ContentCopierIteratingCallback extends IteratingCallback
    {
        private final Content.Source source;
        private final Content.Sink sink;
        private final Predicate<Throwable> onTransientError;
        private final Callback callback;

        public ContentCopierIteratingCallback(Content.Source source, Content.Sink target, Predicate<Throwable> onTransientError, Callback callback)
        {
            this.source = source;
            this.sink = target;
            this.onTransientError = onTransientError;
            this.callback = callback;
        }

        @Override
        protected Action process() throws Throwable
        {
            Content.Chunk chunk = source.read();
            if (chunk == null)
            {
                source.demand(this::succeeded);
                return Action.SCHEDULED;
            }
            if (Content.Chunk.isFailure(chunk, false))
            {
                Throwable failure = chunk.getFailure();
                if (onTransientError.test(failure))
                    throw new IOException(failure);
            }
            if (Content.Chunk.isFailure(chunk, true))
                throw new IOException(chunk.getFailure());

            if (chunk.hasRemaining())
            {
                ByteBuffer byteBuffer = chunk.getByteBuffer();
                sink.write(chunk.isLast(), byteBuffer, Callback.from(chunk::release, this));
                return Action.SCHEDULED;
            }

            chunk.release();
            return chunk.isLast() ? Action.SUCCEEDED : Action.SCHEDULED;
        }

        @Override
        protected void onCompleteSuccess()
        {
            callback.succeeded();
        }

        @Override
        protected void onCompleteFailure(Throwable x)
        {
            source.fail(x);
            callback.failed(x);
        }
    }
}
