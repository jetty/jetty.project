//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.client.util.InputStreamContentProvider;
import org.eclipse.jetty.client.util.OutputStreamContentProvider;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.Assert;
import org.junit.Test;

public class AsyncRequestContentTest extends AbstractTest
{
    public AsyncRequestContentTest(Transport transport)
    {
        super(transport);
    }

    @Test
    public void testEmptyDeferredContent() throws Exception
    {
        start(new ConsumeInputHandler());

        DeferredContentProvider contentProvider = new DeferredContentProvider();
        CountDownLatch latch = new CountDownLatch(1);
        client.POST(newURI())
                .content(contentProvider)
                .send(result ->
                {
                    if (result.isSucceeded() &&
                            result.getResponse().getStatus() == HttpStatus.OK_200)
                        latch.countDown();
                });
        contentProvider.close();

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testDeferredContent() throws Exception
    {
        start(new ConsumeInputHandler());

        DeferredContentProvider contentProvider = new DeferredContentProvider();
        CountDownLatch latch = new CountDownLatch(1);
        client.POST(newURI())
                .content(contentProvider)
                .send(result ->
                {
                    if (result.isSucceeded() &&
                            result.getResponse().getStatus() == HttpStatus.OK_200)
                        latch.countDown();
                });
        contentProvider.offer(ByteBuffer.wrap(new byte[1]));
        contentProvider.close();

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testEmptyInputStream() throws Exception
    {
        start(new ConsumeInputHandler());

        InputStreamContentProvider contentProvider =
                new InputStreamContentProvider(new ByteArrayInputStream(new byte[0]));
        CountDownLatch latch = new CountDownLatch(1);
        client.POST(newURI())
                .content(contentProvider)
                .send(result ->
                {
                    if (result.isSucceeded() &&
                            result.getResponse().getStatus() == HttpStatus.OK_200)
                        latch.countDown();
                });
        contentProvider.close();

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testInputStream() throws Exception
    {
        start(new ConsumeInputHandler());

        InputStreamContentProvider contentProvider =
                new InputStreamContentProvider(new ByteArrayInputStream(new byte[1]));
        CountDownLatch latch = new CountDownLatch(1);
        client.POST(newURI())
                .content(contentProvider)
                .send(result ->
                {
                    if (result.isSucceeded() &&
                            result.getResponse().getStatus() == HttpStatus.OK_200)
                        latch.countDown();
                });
        contentProvider.close();

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testEmptyOutputStream() throws Exception
    {
        start(new ConsumeInputHandler());

        OutputStreamContentProvider contentProvider = new OutputStreamContentProvider();
        CountDownLatch latch = new CountDownLatch(1);
        client.POST(newURI())
                .content(contentProvider)
                .send(result ->
                {
                    if (result.isSucceeded() &&
                            result.getResponse().getStatus() == HttpStatus.OK_200)
                        latch.countDown();
                });
        contentProvider.close();

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testOutputStream() throws Exception
    {
        start(new ConsumeInputHandler());

        OutputStreamContentProvider contentProvider = new OutputStreamContentProvider();
        CountDownLatch latch = new CountDownLatch(1);
        client.POST(newURI())
                .content(contentProvider)
                .send(result ->
                {
                    if (result.isSucceeded() &&
                            result.getResponse().getStatus() == HttpStatus.OK_200)
                        latch.countDown();
                });
        OutputStream output = contentProvider.getOutputStream();
        output.write(new byte[1]);
        output.flush();
        contentProvider.close();

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    private static class ConsumeInputHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            ServletInputStream input = request.getInputStream();
            while (true)
            {
                int read = input.read();
                if (read < 0)
                    break;
            }
            response.setStatus(HttpStatus.OK_200);
        }
    }
}
