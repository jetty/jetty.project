//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.Callback;

public interface HttpStream extends Callback
{
    String getId();

    long getNanoTimeStamp();

    Content readContent();

    void demandContent(); // Calls back on Channel#onDataAvailable

    // TODO add MetaData.Request request.
    void send(MetaData.Response response, boolean last, Callback callback, ByteBuffer... content);

    boolean isPushSupported();

    void push(MetaData.Request request);

    boolean isCommitted();

    boolean isComplete();

    void upgrade(org.eclipse.jetty.io.Connection connection);

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

        public final String getId()
        {
            return _wrapped.getId();
        }

        @Override
        public final long getNanoTimeStamp()
        {
            return _wrapped.getNanoTimeStamp();
        }

        public Content readContent()
        {
            return _wrapped.readContent();
        }

        public void demandContent()
        {
            _wrapped.demandContent();
        }

        public void send(MetaData.Response response, boolean last, Callback callback, ByteBuffer... content)
        {
            _wrapped.send(response, last, callback, content);
        }

        public final boolean isPushSupported()
        {
            return _wrapped.isPushSupported();
        }

        public void push(MetaData.Request request)
        {
            _wrapped.push(request);
        }

        public final boolean isCommitted()
        {
            return _wrapped.isCommitted();
        }

        public final boolean isComplete()
        {
            return _wrapped.isComplete();
        }

        public void upgrade(Connection connection)
        {
            _wrapped.upgrade(connection);
        }

        public final Throwable consumeAll()
        {
            return _wrapped.consumeAll();
        }

        public void succeeded()
        {
            _wrapped.succeeded();
        }

        public void failed(Throwable x)
        {
            _wrapped.failed(x);
        }

        public InvocationType getInvocationType()
        {
            return _wrapped.getInvocationType();
        }
    }
}
