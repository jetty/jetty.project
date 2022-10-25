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

package org.eclipse.jetty.client;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ValidatingConnectionPoolTest extends AbstractHttpClientServerTest
{
    @Override
    public HttpClient newHttpClient(HttpClientTransport transport)
    {
        long timeout = 1000;
        transport.setConnectionPoolFactory(destination ->
            new ValidatingConnectionPool(destination, destination.getHttpClient().getMaxConnectionsPerDestination(), destination, destination.getHttpClient().getScheduler(), timeout));

        return super.newHttpClient(transport);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testRequestAfterValidation(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        client.setMaxConnectionsPerDestination(1);

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());

        // The second request should be sent after the validating timeout.
        response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testServerClosesConnectionAfterRedirectWithoutConnectionCloseHeader(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, Response response) throws Throwable
            {
                if (org.eclipse.jetty.server.Request.getPathInContext(request).endsWith("/redirect"))
                {
                    response.setStatus(HttpStatus.TEMPORARY_REDIRECT_307);
                    response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, 0);
                    response.getHeaders().put(HttpHeader.LOCATION, scenario.getScheme() + "://localhost:" + connector.getLocalPort() + "/");
                    Content.Sink.write(response, false, null);
                    request.getConnectionMetaData().getConnection().getEndPoint().shutdownOutput();
                }
                else
                {
                    response.setStatus(HttpStatus.OK_200);
                    response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, 0);
                    response.getHeaders().put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString());
                }
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/redirect")
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testServerClosesConnectionAfterResponseWithQueuedRequestWithMaxConnectionsWithConnectionCloseHeader(Scenario scenario) throws Exception
    {
        testServerClosesConnectionAfterResponseWithQueuedRequestWithMaxConnections(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, Response response)
            {
                response.setStatus(HttpStatus.OK_200);
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, 0);
                response.getHeaders().put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString());
            }
        });
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testServerClosesConnectionAfterResponseWithQueuedRequestWithMaxConnectionsWithoutConnectionCloseHeader(Scenario scenario) throws Exception
    {
        testServerClosesConnectionAfterResponseWithQueuedRequestWithMaxConnections(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, Response response) throws Throwable
            {
                response.setStatus(HttpStatus.OK_200);
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, 0);
                Content.Sink.write(response, false, null);
                request.getConnectionMetaData().getConnection().getEndPoint().shutdownOutput();
            }
        });
    }

    private void testServerClosesConnectionAfterResponseWithQueuedRequestWithMaxConnections(final Scenario scenario, Handler handler) throws Exception
    {
        start(scenario, handler);
        client.setMaxConnectionsPerDestination(1);

        final CountDownLatch latch = new CountDownLatch(1);
        Request request1 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
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
            .scheme(scenario.getScheme())
            .path("/two");
        FutureResponseListener listener2 = new FutureResponseListener(request2);
        request2.send(listener2);

        // Now we have one request about to be sent, and one queued.

        latch.countDown();

        ContentResponse response1 = listener1.get(5, TimeUnit.SECONDS);
        assertEquals(200, response1.getStatus());

        ContentResponse response2 = listener2.get(5, TimeUnit.SECONDS);
        assertEquals(200, response2.getStatus());
    }
}
