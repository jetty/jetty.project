//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

public class ClientConnectionCloseTest extends AbstractHttpClientServerTest
{
    public ClientConnectionCloseTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Test
    public void testClientConnectionCloseShutdownOutputWithoutRequestContent() throws Exception
    {
        testClientConnectionCloseShutdownOutput(null);
    }

    @Test
    public void testClientConnectionCloseShutdownOutputWithRequestContent() throws Exception
    {
        testClientConnectionCloseShutdownOutput(new StringContentProvider("data", StandardCharsets.UTF_8));
    }

    @Test
    public void testClientConnectionCloseShutdownOutputWithChunkedRequestContent() throws Exception
    {
        DeferredContentProvider content = new DeferredContentProvider()
        {
            @Override
            public long getLength()
            {
                return -1;
            }
        };
        content.offer(ByteBuffer.wrap("data".getBytes(StandardCharsets.UTF_8)));
        content.close();
        testClientConnectionCloseShutdownOutput(content);
    }

    private void testClientConnectionCloseShutdownOutput(ContentProvider content) throws Exception
    {
        AtomicReference<EndPoint> ref = new AtomicReference<>();
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                ref.set(baseRequest.getHttpChannel().getEndPoint());
                ServletInputStream input = request.getInputStream();
                while (true)
                {
                    int read = input.read();
                    if (read < 0)
                        break;
                }
                response.setStatus(HttpStatus.OK_200);
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/ctx/path")
                .header(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString())
                .content(content)
                .send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        // Wait for the FIN to arrive to the server
        Thread.sleep(1000);

        // Do not read from the server because it will trigger
        // the send of the TLS Close Message before the response.

        EndPoint serverEndPoint = ref.get();
        ByteBuffer buffer = BufferUtil.allocate(1);
        int read = serverEndPoint.fill(buffer);
        Assert.assertEquals(-1, read);
    }
}
