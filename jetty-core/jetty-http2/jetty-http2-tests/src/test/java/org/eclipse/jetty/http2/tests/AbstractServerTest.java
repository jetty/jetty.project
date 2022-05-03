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

package org.eclipse.jetty.http2.tests;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.internal.generator.Generator;
import org.eclipse.jetty.http2.internal.parser.Parser;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.http2.server.RawHTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;

public class AbstractServerTest
{
    protected ServerConnector connector;
    protected ByteBufferPool byteBufferPool;
    protected Generator generator;
    protected Server server;
    protected String path;

    protected void startServer(Handler handler) throws Exception
    {
        prepareServer(new HTTP2ServerConnectionFactory(new HttpConfiguration()));
        server.setHandler(handler);
        server.start();
    }

    protected void startServer(ServerSessionListener listener) throws Exception
    {
        prepareServer(new RawHTTP2ServerConnectionFactory(new HttpConfiguration(), listener));
        server.start();
    }

    private void prepareServer(ConnectionFactory connectionFactory)
    {
        QueuedThreadPool serverExecutor = new QueuedThreadPool();
        serverExecutor.setName("server");
        server = new Server(serverExecutor);
        connector = new ServerConnector(server, connectionFactory);
        server.addConnector(connector);
        path = "/test";
        byteBufferPool = new MappedByteBufferPool();
        generator = new Generator(byteBufferPool);
    }

    protected MetaData.Request newRequest(String method, HttpFields fields)
    {
        String host = "localhost";
        int port = connector.getLocalPort();
        String authority = host + ":" + port;
        return new MetaData.Request(method, HttpScheme.HTTP.asString(), new HostPortHttpField(authority), path, HttpVersion.HTTP_2, fields, -1);
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (server != null)
            server.stop();
    }

    protected boolean parseResponse(Socket client, Parser parser) throws IOException
    {
        return parseResponse(client, parser, 1000);
    }

    protected boolean parseResponse(Socket client, Parser parser, long timeout) throws IOException
    {
        byte[] buffer = new byte[2048];
        InputStream input = client.getInputStream();
        client.setSoTimeout((int)timeout);
        while (true)
        {
            try
            {
                int read = input.read(buffer);
                if (read < 0)
                    return true;
                parser.parse(ByteBuffer.wrap(buffer, 0, read));
                if (client.isClosed())
                    return true;
            }
            catch (SocketTimeoutException x)
            {
                return false;
            }
        }
    }
}
