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

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.Assert;
import org.junit.Test;

public class HttpClientIdleTimeoutTest extends AbstractTest
{
    private long idleTimeout = 1000;

    public HttpClientIdleTimeoutTest(Transport transport)
    {
        super(transport);
    }

    @Test
    public void testClientIdleTimeout() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                if (target.equals("/timeout"))
                {
                    AsyncContext asyncContext = request.startAsync();
                    asyncContext.setTimeout(0);
                }
            }
        });
        client.stop();
        client.setIdleTimeout(idleTimeout);
        client.start();

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(newURI())
                .path("/timeout")
                .send(result ->
                {
                    if (result.isFailed())
                        latch.countDown();
                });

        Assert.assertTrue(latch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));

        // Verify that after the timeout we can make another request.
        ContentResponse response = client.newRequest(newURI()).send();
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testRequestIdleTimeout() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                if (target.equals("/timeout"))
                {
                    AsyncContext asyncContext = request.startAsync();
                    asyncContext.setTimeout(0);
                }
            }
        });

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(newURI())
                .path("/timeout")
                .idleTimeout(idleTimeout, TimeUnit.MILLISECONDS)
                .send(result ->
                {
                    if (result.isFailed())
                        latch.countDown();
                });

        Assert.assertTrue(latch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));

        // Verify that after the timeout we can make another request.
        ContentResponse response = client.newRequest(newURI()).send();
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testIdleClientIdleTimeout() throws Exception
    {
        start(new EmptyServerHandler());
        client.stop();
        client.setIdleTimeout(idleTimeout);
        client.start();

        // Make a first request to open a connection.
        ContentResponse response = client.newRequest(newURI()).send();
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        // Let the connection idle timeout.
        Thread.sleep(2 * idleTimeout);

        // Verify that after the timeout we can make another request.
        response = client.newRequest(newURI()).send();
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testIdleServerIdleTimeout() throws Exception
    {
        start(new EmptyServerHandler());
        if (connector instanceof AbstractConnector )
        {
            AbstractConnector.class.cast( connector).setIdleTimeout(idleTimeout);
        }

        ContentResponse response1 = client.newRequest(newURI()).send();
        Assert.assertEquals(HttpStatus.OK_200, response1.getStatus());

        // Let the server idle timeout.
        Thread.sleep(2 * idleTimeout);

        // Make sure we can make another request successfully.
        ContentResponse response2 = client.newRequest(newURI()).send();
        Assert.assertEquals(HttpStatus.OK_200, response2.getStatus());
    }
}
