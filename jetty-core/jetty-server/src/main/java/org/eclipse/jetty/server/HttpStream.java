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

/**
 * A HttpStream is an abstraction that together with {@link MetaData.Request}, represents the
 * flow of data from and to a single request and response cycle.  It is roughly analogous to the
 * Stream within a HTTP/2 connection, in that a connection can have many streams, each used once
 * and each representing a single request and response exchange.
 */
public interface HttpStream extends Callback
{
    /**
     * <p>Attribute name to be used as a {@link Request} attribute to store/retrieve
     * the {@link Connection} created during the HTTP/1.1 upgrade mechanism or the
     * HTTP/2 tunnel mechanism.</p>
     */
    String UPGRADE_CONNECTION_ATTRIBUTE = HttpStream.class.getName() + ".upgradeConnection";

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
     * <p>This method is called from the implementation of {@link Request#demand(Runnable)} and when the
     * demand can be satisfied the implementation must call {@link HttpChannel#onContentAvailable()}.
     * If there is a problem meeting demand, then the implementation must call {@link HttpChannel#onFailure(Throwable)}.</p>
     * @see HttpChannel#onContentAvailable()
     * @see HttpChannel#onFailure(Throwable)
     */
    void demand();

    /**
     * <p>Prepare the response headers with respect to the stream. Typically this may set headers related to
     * protocol specific behaviour (e.g. {@code Keep-Alive} for HTTP/1.0 connections).</p>
     * @param headers The headers to prepare.
     */
    void prepareResponse(HttpFields.Mutable headers);

    /**
     * <p>Send response meta-data and/or data.</p>
     * @param request The request metadata for which the response should be sent.
     * @param response The response metadata to be sent or null if the response is already committed by a previous call
     *                 to send.
     * @param last True if this will be the last call to send and the response can be completed.
     * @param content A buffer of content to send or null if no content.
     * @param callback The callback to invoke when the send is completed successfully or in failure.
     */
    void send(MetaData.Request request, MetaData.Response response, boolean last, ByteBuffer content, Callback callback);

    boolean isPushSupported();

    void push(MetaData.Request request);

    boolean isCommitted();

    default TunnelSupport getTunnelSupport()
    {
        return null;
    }

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
            return getWrapped().getId();
        }

        @Override
        public final long getNanoTimeStamp()
        {
            return getWrapped().getNanoTimeStamp();
        }

        @Override
        public Content.Chunk read()
        {
            return getWrapped().read();
        }

        @Override
        public void demand()
        {
            getWrapped().demand();
        }

        @Override
        public void prepareResponse(HttpFields.Mutable headers)
        {
            getWrapped().prepareResponse(headers);
        }

        @Override
        public void send(MetaData.Request request, MetaData.Response response, boolean last, ByteBuffer content, Callback callback)
        {
            getWrapped().send(request, response, last, content, callback);
        }

        @Override
        public final boolean isPushSupported()
        {
            return getWrapped().isPushSupported();
        }

        @Override
        public void push(MetaData.Request request)
        {
            getWrapped().push(request);
        }

        @Override
        public final boolean isCommitted()
        {
            return getWrapped().isCommitted();
        }

        @Override
        public TunnelSupport getTunnelSupport()
        {
            return getWrapped().getTunnelSupport();
        }

        @Override
        public final Throwable consumeAvailable()
        {
            return getWrapped().consumeAvailable();
        }

        @Override
        public void succeeded()
        {
            getWrapped().succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            getWrapped().failed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return getWrapped().getInvocationType();
        }
    }
}
