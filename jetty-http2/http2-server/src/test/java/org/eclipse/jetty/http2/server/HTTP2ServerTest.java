//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http2.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.http2.hpack.MetaData;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.Callback;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class HTTP2ServerTest
{
    private Server server;
    private ServerConnector connector;
    private String path;
    private ByteBufferPool byteBufferPool;
    private Generator generator;

    private void startServer(HttpServlet servlet) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server, new HTTP2ServerConnectionFactory(new HttpConfiguration()));
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(server, "/");
        path = "/test";
        context.addServlet(new ServletHolder(servlet), path);

        byteBufferPool = new MappedByteBufferPool();
        generator = new Generator(byteBufferPool);

        server.start();
    }

    @After
    public void dispose() throws Exception
    {
        server.stop();
    }

    @Test
    public void testRequestResponseNoContent() throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
            }
        });

        String host = "localhost";
        int port = connector.getLocalPort();
        HttpFields fields = new HttpFields();
        MetaData.Request metaData = new MetaData.Request(HttpScheme.HTTP, HttpMethod.GET.asString(),
                host + ":" + port, host, port, path, fields);
        HeadersFrame request = new HeadersFrame(1, metaData, null, true);
        Generator.LeaseCallback lease = generator.generate(request, Callback.Adapter.INSTANCE);

        try (SocketChannel client = SocketChannel.open(new InetSocketAddress(host, port)))
        {
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                client.write(buffer);
            }

            ByteBuffer buffer = ByteBuffer.allocate(2048);
            client.read(buffer);
            buffer.flip();

            final AtomicReference<HeadersFrame> frameRef = new AtomicReference<>();
            Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
            {
                @Override
                public boolean onHeaders(HeadersFrame frame)
                {
                    frameRef.set(frame);
                    return false;
                }
            });

            parser.parse(buffer);

            HeadersFrame response = frameRef.get();
            Assert.assertNotNull(response);
        }

    }
}
