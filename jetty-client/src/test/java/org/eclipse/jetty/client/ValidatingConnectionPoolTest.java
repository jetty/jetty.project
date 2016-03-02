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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.http.HttpDestinationOverHTTP;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

public class ValidatingConnectionPoolTest extends AbstractHttpClientServerTest
{
    public ValidatingConnectionPoolTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Override
    protected void startClient() throws Exception
    {
        startClient(new ValidatingHttpClientTransportOverHTTP(1000));
    }

    @Test
    public void testRequestAfterValidation() throws Exception
    {
        start(new EmptyServerHandler());

        client.setMaxConnectionsPerDestination(1);

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send();
        Assert.assertEquals(200, response.getStatus());

        // The second request should be sent after the validating timeout.
        response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send();
        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void testServerClosesConnectionAfterRedirectWithoutConnectionCloseHeader() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                if (target.endsWith("/redirect"))
                {
                    response.setStatus(HttpStatus.TEMPORARY_REDIRECT_307);
                    response.setContentLength(0);
                    response.setHeader(HttpHeader.LOCATION.asString(), scheme + "://localhost:" + connector.getLocalPort() + "/");
                    response.flushBuffer();
                    baseRequest.getHttpChannel().getEndPoint().shutdownOutput();
                }
                else
                {
                    response.setStatus(HttpStatus.OK_200);
                    response.setContentLength(0);
                    response.setHeader(HttpHeader.CONNECTION.asString(), HttpHeaderValue.CLOSE.asString());
                }
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/redirect")
                .send();
        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void testServerClosesConnectionAfterResponseWithQueuedRequestWithMaxConnectionsWithConnectionCloseHeader() throws Exception
    {
        testServerClosesConnectionAfterResponseWithQueuedRequestWithMaxConnections(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setStatus(HttpStatus.OK_200);
                response.setContentLength(0);
                response.setHeader(HttpHeader.CONNECTION.asString(), HttpHeaderValue.CLOSE.asString());
            }
        });
    }

    @Test
    public void testServerClosesConnectionAfterResponseWithQueuedRequestWithMaxConnectionsWithoutConnectionCloseHeader() throws Exception
    {
        testServerClosesConnectionAfterResponseWithQueuedRequestWithMaxConnections(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setStatus(HttpStatus.OK_200);
                response.setContentLength(0);
                response.flushBuffer();
                baseRequest.getHttpChannel().getEndPoint().shutdownOutput();
            }
        });
    }

    private void testServerClosesConnectionAfterResponseWithQueuedRequestWithMaxConnections(Handler handler) throws Exception
    {
        start(handler);
        client.setMaxConnectionsPerDestination(1);

        final CountDownLatch latch = new CountDownLatch(1);
        Request request1 = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/one")
                .onRequestBegin(r ->
                {
                    try
                    {
                        latch.await();
                    }
                    catch (InterruptedException x)
                    {
                        r.abort(x);
                    }
                });
        FutureResponseListener listener1 = new FutureResponseListener(request1);
        request1.send(listener1);

        Request request2 = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/two");
        FutureResponseListener listener2 = new FutureResponseListener(request2);
        request2.send(listener2);

        // Now we have one request about to be sent, and one queued.

        latch.countDown();

        ContentResponse response1 = listener1.get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response1.getStatus());

        ContentResponse response2 = listener2.get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response2.getStatus());
    }

    private static class ValidatingHttpClientTransportOverHTTP extends HttpClientTransportOverHTTP
    {
        private final long timeout;

        public ValidatingHttpClientTransportOverHTTP(long timeout)
        {
            super(1);
            this.timeout = timeout;
        }

        @Override
        public HttpDestination newHttpDestination(Origin origin)
        {
            return new HttpDestinationOverHTTP(getHttpClient(), origin)
            {
                @Override
                protected DuplexConnectionPool newConnectionPool(HttpClient client)
                {
                    return new ValidatingConnectionPool(this, client.getMaxConnectionsPerDestination(), this, client.getScheduler(), timeout);
                }
            };
        }
    }
}
