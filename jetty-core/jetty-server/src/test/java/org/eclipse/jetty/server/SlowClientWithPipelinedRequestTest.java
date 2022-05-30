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

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.internal.HttpConnection;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SlowClientWithPipelinedRequestTest
{
    private final AtomicInteger handles = new AtomicInteger();
    private Server server;
    private ServerConnector connector;

    public void startServer(Handler handler) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server, new HttpConnectionFactory()
        {
            @Override
            public Connection newConnection(Connector connector, EndPoint endPoint)
            {
                return configure(new HttpConnection(getHttpConfiguration(), connector, endPoint, isRecordHttpComplianceViolations())
                {
                    @Override
                    public void onFillable()
                    {
                        handles.incrementAndGet();
                        super.onFillable();
                    }
                }, connector, endPoint);
            }
        });

        server.addConnector(connector);
        connector.setPort(0);
        server.setHandler(handler);
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        if (server != null)
        {
            server.stop();
            server.join();
        }
    }

    @Test
    public void testSlowClientWithPipelinedRequest() throws Exception
    {
        final int contentLength = 512 * 1024;
        startServer(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                if ("/content".equals(request.getPathInContext()))
                {
                    // TODO is this still a valid test?
                    // We simulate what the DefaultServlet does, bypassing the blocking
                    // write mechanism otherwise the test does not reproduce the bug

                    // Since the test is via localhost, we need a really big buffer to stall the write
                    byte[] bytes = new byte[contentLength];
                    Arrays.fill(bytes, (byte)'9');
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    // Do a non blocking write
                    response.write(true, buffer, callback);
                }
                else
                {
                    callback.succeeded();
                }
            }
        });

        Socket client = new Socket("localhost", connector.getLocalPort());
        OutputStream output = client.getOutputStream();
        output.write((
            "GET /content HTTP/1.1\r\n" +
                "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                "\r\n" +
                "").getBytes(StandardCharsets.UTF_8));
        output.flush();

        InputStream input = client.getInputStream();

        int read = input.read();
        assertTrue(read >= 0);
        // As soon as we can read the response, send a pipelined request
        // so it is a different read for the server and it will trigger NIO
        output.write((
            "GET /pipelined HTTP/1.1\r\n" +
                "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                "\r\n" +
                "").getBytes(StandardCharsets.UTF_8));
        output.flush();

        // Simulate a slow reader
        Thread.sleep(1000);
        assertThat(handles.get(), lessThan(10));

        // We are sure we are not spinning, read the content
        StringBuilder lines = new StringBuilder().append((char)read);
        int crlfs = 0;
        while (true)
        {
            read = input.read();
            lines.append((char)read);
            if (read == '\r' || read == '\n')
                ++crlfs;
            else
                crlfs = 0;
            if (crlfs == 4)
                break;
        }
        assertThat(lines.toString(), containsString(" 200 "));
        // Read the body
        for (int i = 0; i < contentLength; ++i)
        {
            input.read();
        }

        // Read the pipelined response
        lines.setLength(0);
        crlfs = 0;
        while (true)
        {
            read = input.read();
            lines.append((char)read);
            if (read == '\r' || read == '\n')
                ++crlfs;
            else
                crlfs = 0;
            if (crlfs == 4)
                break;
        }
        assertThat(lines.toString(), containsString(" 200 "));

        client.close();
    }
}
