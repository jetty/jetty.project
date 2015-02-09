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

package org.eclipse.jetty.http.client;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.Assert;
import org.junit.Test;

public class HttpClientTest extends AbstractTest
{
    public HttpClientTest(Transport transport)
    {
        super(transport);
    }

    @Test
    public void testRequestWithoutResponseContent() throws Exception
    {
        final int status = HttpStatus.NO_CONTENT_204;
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setStatus(status);
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(status, response.getStatus());
        Assert.assertEquals(0, response.getContent().length);
    }

    @Test
    public void testRequestWithSmallResponseContent() throws Exception
    {
        testRequestWithResponseContent(1024);
    }

    @Test
    public void testRequestWithLargeResponseContent() throws Exception
    {
        testRequestWithResponseContent(1024 * 1024);
    }

    private void testRequestWithResponseContent(int length) throws Exception
    {
        final byte[] bytes = new byte[length];
        new Random().nextBytes(bytes);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().write(bytes);
            }
        });

        org.eclipse.jetty.client.api.Request request = client.newRequest("localhost", connector.getLocalPort());
        FutureResponseListener listener = new FutureResponseListener(request, length);
        request.timeout(10, TimeUnit.SECONDS).send(listener);
        ContentResponse response = listener.get();

        Assert.assertEquals(200, response.getStatus());
        Assert.assertArrayEquals(bytes, response.getContent());
    }

    @Test
    public void testRequestWithSmallResponseContentChunked() throws Exception
    {
        testRequestWithResponseContentChunked(512);
    }

    @Test
    public void testRequestWithLargeResponseContentChunked() throws Exception
    {
        testRequestWithResponseContentChunked(512 * 512);
    }

    private void testRequestWithResponseContentChunked(int length) throws Exception
    {
        final byte[] chunk1 = new byte[length];
        final byte[] chunk2 = new byte[length];
        Random random = new Random();
        random.nextBytes(chunk1);
        random.nextBytes(chunk2);
        byte[] bytes = new byte[chunk1.length + chunk2.length];
        System.arraycopy(chunk1, 0, bytes, 0, chunk1.length);
        System.arraycopy(chunk2, 0, bytes, chunk1.length, chunk2.length);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                ServletOutputStream output = response.getOutputStream();
                output.write(chunk1);
                output.flush();
                output.write(chunk2);
            }
        });

        org.eclipse.jetty.client.api.Request request = client.newRequest("localhost", connector.getLocalPort());
        FutureResponseListener listener = new FutureResponseListener(request, 2 * length);
        request.timeout(10, TimeUnit.SECONDS).send(listener);
        ContentResponse response = listener.get();

        Assert.assertEquals(200, response.getStatus());
        Assert.assertArrayEquals(bytes, response.getContent());
    }

    @Test
    public void testUploadZeroLengthWithoutResponseContent() throws Exception
    {
        testUploadWithoutResponseContent(0);
    }

    @Test
    public void testUploadSmallWithoutResponseContent() throws Exception
    {
        testUploadWithoutResponseContent(1024);
    }

    @Test
    public void testUploadLargeWithoutResponseContent() throws Exception
    {
        testUploadWithoutResponseContent(1024 * 1024);
    }

    private void testUploadWithoutResponseContent(int length) throws Exception
    {
        final byte[] bytes = new byte[length];
        new Random().nextBytes(bytes);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                ServletInputStream input = request.getInputStream();
                for (int i = 0; i < bytes.length; ++i)
                    Assert.assertEquals(bytes[i] & 0xFF, input.read());
                Assert.assertEquals(-1, input.read());
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .method(HttpMethod.POST)
                .content(new BytesContentProvider(bytes))
                .timeout(15, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
        Assert.assertEquals(0, response.getContent().length);
    }
}
