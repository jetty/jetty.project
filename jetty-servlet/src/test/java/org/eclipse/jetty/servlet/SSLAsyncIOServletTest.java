//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.servlet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collection;
import java.util.Random;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.http.SimpleHttpParser;
import org.eclipse.jetty.toolchain.test.http.SimpleHttpResponse;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class SSLAsyncIOServletTest
{
    @Parameterized.Parameters
    public static Collection<SslContextFactory[]> parameters()
    {
        return Arrays.asList(new SslContextFactory[]{null}, new SslContextFactory[]{new SslContextFactory()});
    }

    private Server server;
    private ServerConnector connector;
    private SslContextFactory sslContextFactory;
    private String contextPath;
    private String servletPath;

    public SSLAsyncIOServletTest(SslContextFactory sslContextFactory)
    {
        this.sslContextFactory = sslContextFactory;
        if (sslContextFactory != null)
        {
            sslContextFactory.setEndpointIdentificationAlgorithm("");
            sslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
            sslContextFactory.setKeyStorePassword("storepwd");
            sslContextFactory.setTrustStorePath("src/test/resources/truststore.jks");
            sslContextFactory.setTrustStorePassword("storepwd");
        }
    }

    public void prepare(HttpServlet servlet) throws Exception
    {
        server = new Server();

        connector = new ServerConnector(server, sslContextFactory);
        server.addConnector(connector);

        contextPath = "/context";
        ServletContextHandler context = new ServletContextHandler(server, contextPath, true, false);
        servletPath = "/servlet";
        context.addServlet(new ServletHolder(servlet), servletPath);

        server.start();
    }

    @After
    public void dispose() throws Exception
    {
        server.stop();
    }

    @Test
    public void testAsyncIOWritesWithAggregation() throws Exception
    {
        Random random = new Random();
        String chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        final byte[] content = new byte[50000];
        for (int i = 0; i < content.length; ++i)
            content[i] = (byte)chars.charAt(random.nextInt(chars.length()));

        prepare(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                final AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                final int bufferSize = 4096;
                response.setBufferSize(bufferSize);
                response.getOutputStream().setWriteListener(new WriteListener()
                {
                    private int writes;
                    private int written;

                    @Override
                    public void onWritePossible() throws IOException
                    {
                        ServletOutputStream output = asyncContext.getResponse().getOutputStream();
                        do
                        {
                            int toWrite = content.length - written;
                            if (toWrite == 0)
                            {
                                asyncContext.complete();
                                return;
                            }

                            toWrite = Math.min(toWrite, bufferSize);

                            // Perform a write that is smaller than the buffer size to
                            // trigger the condition where the bytes are aggregated.
                            if (writes == 1)
                                toWrite -= 16;

                            output.write(content, written, toWrite);
                            ++writes;
                            written += toWrite;
                        }
                        while (output.isReady());
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        asyncContext.complete();
                    }
                });
            }
        });

        try (Socket client = newClient())
        {
            String request = "" +
                    "GET " + contextPath + servletPath + " HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n";
            OutputStream output = client.getOutputStream();
            output.write(request.getBytes("UTF-8"));
            output.flush();

            BufferedReader input = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
            SimpleHttpParser parser = new SimpleHttpParser();
            SimpleHttpResponse response = parser.readResponse(input);
            Assert.assertEquals("200", response.getCode());
            Assert.assertArrayEquals(content, response.getBody().getBytes("UTF-8"));
        }
    }

    private Socket newClient() throws IOException
    {
        return sslContextFactory == null ? new Socket("localhost", connector.getLocalPort())
                : sslContextFactory.getSslContext().getSocketFactory().createSocket("localhost", connector.getLocalPort());
    }
}
