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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.Content.Chunk;
import org.eclipse.jetty.util.Callback;

public interface HttpStream extends Callback
{
    /**
     * @return an ID unique within the lifetime scope of the associated protocol connection.
     * This may be a protocol ID (eg HTTP/2 stream ID) or it may be unrelated to the protocol.
     */
    String getId();

    /**
     * @return the nanoTime when this HttpStream was created
     */
    long getNanoTimeStamp();

    /**
     * <p>Reads a chunk of content, with the same semantic as {@link Content.Source#read()}.</p>
     * <p>This method is called from the implementation of {@link Request#read()}.</p>
     *
     * @return a chunk of content, possibly an {@link Chunk.Error error} or {@code null}.
     */
    Content.Chunk read();

    /**
     * <p>Demands more content chunks to the underlying implementation.</p>
     * <p>This method is called from the implementation of {@link Request#demand(Runnable)}.</p>
     */
    void demand();

    void prepareResponse(HttpFields.Mutable headers);

    void send(MetaData.Request request, MetaData.Response response, boolean last, Callback callback, ByteBuffer... content);

    boolean isPushSupported();

    void push(MetaData.Request request);

    boolean isCommitted();

    // TODO: remove this method? Only used in tests.
    boolean isComplete();

    void setUpgradeConnection(Connection connection);

    Connection upgrade();

    default Throwable consumeAvailable()
    {
        while (true)
        {
            // We can always just read again here as EOF and Error content will be persistently returned.
            Content.Chunk content = read();

            // if we cannot read to EOF then fail the stream rather than wait for unconsumed content
            if (content == null)
                return new IOException("Content not consumed");

            // Always release any returned content. This is a noop for EOF and Error content.
            content.release();

            // if the input failed, then fail the stream for same reason
            if (content instanceof Content.Chunk.Error error)
                return error.getCause();

            if (content.isLast())
                return null;
        }
    }

    class Wrapper implements HttpStream
    {
        private final HttpStream _wrapped;

        public Wrapper(HttpStream wrapped)
        {
            _wrapped = wrapped;
        }

        public HttpStream getWrapped()
        {
            return _wrapped;
        }

        @Override
        public final String getId()
        {
            return _wrapped.getId();
        }

        @Override
        public final long getNanoTimeStamp()
        {
            return _wrapped.getNanoTimeStamp();
        }

        @Override
        public Content.Chunk read()
        {
            return _wrapped.read();
        }

        @Override
        public void demand()
        {
            _wrapped.demand();
        }

        @Override
        public void prepareResponse(HttpFields.Mutable headers)
        {
            _wrapped.prepareResponse(headers);
        }

        @Override
        public void send(MetaData.Request request, MetaData.Response response, boolean last, Callback callback, ByteBuffer... content)
        {
            _wrapped.send(request, response, last, callback, content);
        }

        @Override
        public final boolean isPushSupported()
        {
            return _wrapped.isPushSupported();
        }

        @Override
        public void push(MetaData.Request request)
        {
            _wrapped.push(request);
        }

        @Override
        public final boolean isCommitted()
        {
            return _wrapped.isCommitted();
        }

        @Override
        public final boolean isComplete()
        {
            return _wrapped.isComplete();
        }

        @Override
        public void setUpgradeConnection(Connection connection)
        {
            _wrapped.setUpgradeConnection(connection);
        }

        @Override
        public Connection upgrade()
        {
            return _wrapped.upgrade();
        }

        @Override
        public final Throwable consumeAvailable()
        {
            return _wrapped.consumeAvailable();
        }

        @Override
        public void succeeded()
        {
            _wrapped.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            _wrapped.failed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return _wrapped.getInvocationType();
        }
    }
}
