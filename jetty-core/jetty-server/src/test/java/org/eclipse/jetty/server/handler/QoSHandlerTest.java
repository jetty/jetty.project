//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class QoSHandlerTest
{
    private Server server;
    private LocalConnector connector;

    private void start(QoSHandler qosHandler) throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);
        server.setHandler(qosHandler);
        server.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testMaxRequests() throws Exception
    {
        QoSHandler qosHandler = new QoSHandler();
        // Use heuristics for maxRequests.
        qosHandler.setMaxRequests(0);
        start(qosHandler);

        assertThat(qosHandler.getMaxRequests(), greaterThan(0));
    }

    @Test
    public void testRequestIsSuspendedAndResumed() throws Exception
    {
        int maxRequests = 2;
        QoSHandler qosHandler = new QoSHandler();
        qosHandler.setMaxRequests(maxRequests);
        List<Callback> callbacks = new ArrayList<>();
        qosHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Save the callback but do not succeed it yet.
                callbacks.add(callback);
                return true;
            }
        });
        start(qosHandler);

        List<LocalConnector.LocalEndPoint> endPoints = new ArrayList<>();
        for (int i = 0; i < maxRequests; ++i)
        {
            LocalConnector.LocalEndPoint endPoint = connector.executeRequest("""
                GET /%d HTTP/1.1
                Host: localhost
                
                """.formatted(i));
            endPoints.add(endPoint);
            // Wait that the request arrives at the server.
            await().atMost(5, TimeUnit.SECONDS).until(callbacks::size, is(i + 1));
        }

        // Send one more request, it should be suspended by QoSHandler.
        LocalConnector.LocalEndPoint endPoint = connector.executeRequest("""
                GET /%d HTTP/1.1
                Host: localhost
                
                """.formatted(maxRequests));
        endPoints.add(endPoint);

        assertEquals(maxRequests, callbacks.size());
        await().atMost(5, TimeUnit.SECONDS).until(qosHandler::getSuspendedRequests, is(1L));

        // Finish and verify the waiting requests.
        List<Callback> copy = List.copyOf(callbacks);
        callbacks.clear();
        for (int i = 0; i < copy.size(); ++i)
        {
            Callback callback = copy.get(i);
            callback.succeeded();
            String text = endPoints.get(i).getResponse(false, 5, TimeUnit.SECONDS);
            HttpTester.Response response = HttpTester.parseResponse(text);
            assertEquals(HttpStatus.OK_200, response.getStatus());
        }

        // The suspended request should have been resumed.
        await().atMost(5, TimeUnit.SECONDS).until(qosHandler::getSuspendedRequests, is(0L));
        await().atMost(5, TimeUnit.SECONDS).until(callbacks::size, is(1));

        // Finish the resumed request that is now waiting.
        callbacks.get(0).succeeded();

        String text = endPoints.get(endPoints.size() - 1).getResponse(false, 5, TimeUnit.SECONDS);
        HttpTester.Response response = HttpTester.parseResponse(text);
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testSuspendedRequestTimesOut() throws Exception
    {
        int maxRequests = 1;
        QoSHandler qosHandler = new QoSHandler();
        qosHandler.setMaxRequests(maxRequests);
        long timeout = 1000;
        qosHandler.setMaxSuspend(Duration.ofMillis(timeout));
        List<Callback> callbacks = new ArrayList<>();
        qosHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Save the callback but do not succeed it yet.
                callbacks.add(callback);
                return true;
            }
        });
        start(qosHandler);

        LocalConnector.LocalEndPoint endPoint0 = connector.executeRequest("""
                GET /0 HTTP/1.1
                Host: localhost
                
                """);
        await().atMost(5, TimeUnit.SECONDS).until(callbacks::size, is(1));

        // This request is suspended by QoSHandler.
        LocalConnector.LocalEndPoint endPoint1 = connector.executeRequest("""
                GET /1 HTTP/1.1
                Host: localhost
                
                """);
        await().atMost(5, TimeUnit.SECONDS).until(qosHandler::getSuspendedRequests, is(1L));

        // Do not succeed the callback of the first request.
        // Wait for the second request to time out.
        await().atMost(2 * timeout, TimeUnit.MILLISECONDS).until(qosHandler::getSuspendedRequests, is(0L));

        String text = endPoint1.getResponse(false, 5, TimeUnit.SECONDS);
        HttpTester.Response response = HttpTester.parseResponse(text);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE_503, response.getStatus());

        // Complete the first request callback, e.g. by failing it.
        callbacks.remove(0).failed(new EofException());
        text = endPoint0.getResponse(false, 5, TimeUnit.SECONDS);
        response = HttpTester.parseResponse(text);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR_500, response.getStatus());

        // Verify that a third request goes through.
        LocalConnector.LocalEndPoint endPoint2 = connector.executeRequest("""
                GET /2 HTTP/1.1
                Host: localhost
                
                """);
        await().atMost(5, TimeUnit.SECONDS).until(callbacks::size, is(1));
        callbacks.remove(0).succeeded();
        text = endPoint2.getResponse(false, 5, TimeUnit.SECONDS);
        response = HttpTester.parseResponse(text);
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testRequestPriority() throws Exception
    {
        QoSHandler qosHandler = new QoSHandler()
        {
            @Override
            protected int getPriority(Request request)
            {
                return (int)request.getHeaders().getLongField("Priority");
            }
        };
        qosHandler.setMaxRequests(1);
        qosHandler.setMaxSuspend(Duration.ofSeconds(5));
        qosHandler.setMaxPriority(1);
        List<Callback> callbacks = new ArrayList<>();
        qosHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Save the callback but do not succeed it yet.
                callbacks.add(callback);
                return true;
            }
        });
        start(qosHandler);

        // Make a first request.
        LocalConnector.LocalEndPoint endPoint0 = connector.executeRequest("""
                GET /0 HTTP/1.1
                Host: localhost
                
                """);
        await().atMost(5, TimeUnit.SECONDS).until(callbacks::size, is(1));

        // Make a second request, will be suspended
        LocalConnector.LocalEndPoint endPoint1 = connector.executeRequest("""
                GET /1 HTTP/1.1
                Host: localhost
                Priority: 0
                
                """);
        await().atMost(5, TimeUnit.SECONDS).until(callbacks::size, is(1));

        // Make a third request, will be suspended.
        LocalConnector.LocalEndPoint endPoint2 = connector.executeRequest("""
                GET /2 HTTP/1.1
                Host: localhost
                Priority: 1
                
                """);
        await().atMost(5, TimeUnit.SECONDS).until(callbacks::size, is(1));

        // Complete the first request.
        callbacks.remove(0).succeeded();
        String text = endPoint0.getResponse(false, 5, TimeUnit.SECONDS);
        HttpTester.Response response = HttpTester.parseResponse(text);
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // The second request should not be resumed yet.
        assertNull(endPoint1.getResponse(false, 1, TimeUnit.SECONDS));

        // The third request should have been resumed.
        await().atMost(5, TimeUnit.SECONDS).until(callbacks::size, is(1));
        callbacks.remove(0).succeeded();
        text = endPoint2.getResponse(false, 5, TimeUnit.SECONDS);
        response = HttpTester.parseResponse(text);
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // The second request should now be resumed.
        await().atMost(5, TimeUnit.SECONDS).until(callbacks::size, is(1));
        callbacks.remove(0).succeeded();
        text = endPoint1.getResponse(false, 5, TimeUnit.SECONDS);
        response = HttpTester.parseResponse(text);
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }
}
