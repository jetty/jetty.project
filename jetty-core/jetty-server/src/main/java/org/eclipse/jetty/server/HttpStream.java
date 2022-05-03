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
import org.eclipse.jetty.util.Callback;

public interface HttpStream extends Callback
{
    /**
     * @return an ID unique within the lifetime scope of the associated protocol connection.
     * This may be a protocol ID (eg HTTP/2 stream ID) or it may be unrelated to the protocol.
     * @see HttpStream#getId();
     */
    String getId();

    long getNanoTimeStamp();

    Content readContent();

    void demandContent(); // Calls back on Channel#onDataAvailable

    void prepareResponse(HttpFields.Mutable headers);

    void send(MetaData.Request request, MetaData.Response response, boolean last, Callback callback, ByteBuffer... content);

    boolean isPushSupported();

    void push(MetaData.Request request);

    boolean isCommitted();

    // TODO: remove this method? Only used in tests.
    boolean isComplete();

    void setUpgradeConnection(Connection connection);

    Connection upgrade();

    default Throwable consumeAll()
    {
        while (true)
        {
            // We can always just read again here as EOF and Error content will be persistently returned.
            Content content = readContent();

            // if we cannot read to EOF then fail the stream rather than wait for unconsumed content
            if (content == null)
                return new IOException("Content not consumed");

            // Always release any returned content. This is a noop for EOF and Error content.
            content.release();

            // if the input failed, then fail the stream for same reason
            if (content instanceof Content.Error)
                return ((Content.Error)content).getCause();

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
        public Content readContent()
        {
            return _wrapped.readContent();
        }

        @Override
        public void demandContent()
        {
            _wrapped.demandContent();
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
        public final Throwable consumeAll()
        {
            return _wrapped.consumeAll();
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
