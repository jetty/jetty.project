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

package org.eclipse.jetty.fcgi.server;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.generator.ClientGenerator;
import org.eclipse.jetty.fcgi.parser.ClientParser;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

public class ServerFCGIConnectionTest
{
    private ArrayByteBufferPool.Tracking serverBufferPool;
    private Server server;
    private LocalConnector connector;

    @BeforeEach
    public void prepare() throws Exception
    {
        server = new Server();
        serverBufferPool = new ArrayByteBufferPool.Tracking();
        connector = new LocalConnector(server, null, null, serverBufferPool, -1, new ServerFCGIConnectionFactory(new HttpConfiguration()));
        server.addConnector(connector);
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });
        server.start();
    }

    @AfterEach
    public void dispose()
    {
        try
        {
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat("Server Leaks: " + serverBufferPool.dumpLeaks(), serverBufferPool.getLeaks().size(), is(0)));
        }
        finally
        {
            LifeCycle.stop(server);
        }
    }

    @Test
    public void testRequestRecordsInSeparateNetworkReads() throws Exception
    {
        ByteBufferPool.Accumulator accumulator = new ByteBufferPool.Accumulator();
        ClientGenerator generator = new ClientGenerator(new ArrayByteBufferPool());
        int id = 1;
        HttpFields fields = HttpFields.build()
            .put(FCGI.Headers.REQUEST_METHOD, "GET")
            .put(FCGI.Headers.REQUEST_URI, "/")
            .put(FCGI.Headers.SERVER_PROTOCOL, "HTTP/1.1")
            .put(FCGI.Headers.SERVER_NAME, "localhost")
            .put(FCGI.Headers.SERVER_PORT, "80");
        generator.generateRequestHeaders(accumulator, id, fields);
        generator.generateRequestContent(accumulator, id, null, true);
        List<ByteBuffer> buffers = accumulator.getByteBuffers();

        LocalConnector.LocalEndPoint endPoint = connector.connect();

        // The BEGIN_REQUEST record is read alone, and the parsing
        // ends exactly at the end of the record and of the buffer.
        ByteBuffer beginRequest = BufferUtil.copy(buffers.get(0));
        endPoint.addInput(beginRequest);
        await().atMost(5, TimeUnit.SECONDS).until(() -> !beginRequest.hasRemaining());

        // The server must still read the rest of the request.
        for (ByteBuffer buffer : buffers.subList(1, buffers.size()))
        {
            endPoint.addInput(BufferUtil.copy(buffer));
        }

        AtomicBoolean end = new AtomicBoolean();
        ClientParser parser = new ClientParser(new ClientParser.Listener()
        {
            @Override
            public boolean onEnd(int request)
            {
                end.set(true);
                return true;
            }
        });
        while (!end.get())
        {
            ByteBuffer output = endPoint.waitForOutput(5, TimeUnit.SECONDS);
            assertThat(output, notNullValue());
            while (output.hasRemaining())
            {
                parser.parse(output);
            }
        }
        assertThat(end.get(), is(true));

        accumulator.release();
    }
}
