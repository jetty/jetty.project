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

package org.eclipse.jetty.fcgi.server.internal;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.generator.ClientGenerator;
import org.eclipse.jetty.fcgi.server.ServerFCGIConnectionFactory;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ServerFCGIConnectionTest
{
    @Test
    public void testApplicationDispatchedAfterParsing()
    {
        AtomicInteger handlersInvoked = new AtomicInteger();
        Server server = new Server();
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                handlersInvoked.incrementAndGet();
                callback.succeeded();
                return true;
            }
        });

        ArrayByteBufferPool.Tracking bufferPool = new ArrayByteBufferPool.Tracking();
        ServerFCGIConnectionFactory connectionFactory = new ServerFCGIConnectionFactory(new HttpConfiguration());
        ServerConnector connector = new ServerConnector(server, Runnable::run, null, bufferPool, 0, 1, connectionFactory);
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint(new byte[0], 64 * 1024);
        ServerFCGIConnection connection = new ServerFCGIConnection(connector, endPoint, new HttpConfiguration(), false);

        ClientGenerator generator = new ClientGenerator(ByteBufferPool.NON_POOLING);
        ByteBufferPool.Accumulator accumulator = new ByteBufferPool.Accumulator();
        HttpFields.Mutable params = HttpFields.build()
            .put(FCGI.Headers.REQUEST_METHOD, "GET")
            .put(FCGI.Headers.DOCUMENT_URI, "/")
            .put(FCGI.Headers.QUERY_STRING, "")
            .put(FCGI.Headers.SERVER_PROTOCOL, HttpVersion.HTTP_1_1.asString());
        generator.generateRequestHeaders(accumulator, 1, params);
        generator.generateRequestContent(accumulator, 1, BufferUtil.EMPTY_BUFFER, true);
        List<ByteBuffer> buffers = accumulator.getByteBuffers();
        ByteBuffer request = ByteBuffer.allocate((int)accumulator.getTotalLength());
        buffers.forEach(request::put);
        accumulator.release();
        BufferUtil.flipToFlush(request, 0);

        endPoint.addInput(request);
        connection.onFillable();

        assertEquals(1, handlersInvoked.get());
        assertEquals(0, bufferPool.getLeaks().size(), bufferPool.dumpLeaks());
    }
}
