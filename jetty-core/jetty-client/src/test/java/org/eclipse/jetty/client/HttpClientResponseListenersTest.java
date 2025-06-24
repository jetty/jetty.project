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

package org.eclipse.jetty.client;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientResponseListenersTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testResponseListenerForMultipleEventsIsInvokedOncePerEvent(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        List<String> events = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);
        Response.Listener listener = new Response.Listener()
        {
            @Override
            public void onBegin(Response response)
            {
                events.add("begin");
            }

            @Override
            public boolean onHeader(Response response, HttpField field)
            {
                // Number of header may vary, so don't count
                return true;
            }

            @Override
            public void onHeaders(Response response)
            {
                events.add("headers");
            }

            @Override
            public void onContent(Response response, ByteBuffer content)
            {
                // Should not be invoked.
                events.add("content");
            }

            @Override
            public void onContent(Response response, Content.Chunk chunk, Runnable demander)
            {
                // Should not be invoked.
                events.add("content");
            }

            @Override
            public void onSuccess(Response response)
            {
                events.add("success");
            }

            @Override
            public void onFailure(Response response, Throwable failure)
            {
                // Should not be invoked.
                events.add("failure");
            }

            @Override
            public void onComplete(Result result)
            {
                assertEquals(200, result.getResponse().getStatus());
                events.add("complete");
                latch.countDown();
            }
        };
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseBegin(listener)
            .onResponseHeader(listener)
            .onResponseHeaders(listener)
            .onResponseContent(listener)
            .onResponseContentAsync(listener)
            .onResponseSuccess(listener)
            .onResponseFailure(listener)
            .onComplete(listener)
            .send(listener);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        // The events are duplicated, because the same listener
        // instance is used explicitly in the onResponseXYZ()
        // events, and as a listener passed to send(listener).
        assertThat(events, contains("begin", "begin", "headers", "headers", "success", "success", "complete", "complete"));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testResponseListenerAddedInResponseListenerEvent(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        CountDownLatch beginLatch = new CountDownLatch(1);
        CountDownLatch headersLatch = new CountDownLatch(1);
        var request = client.newRequest("localhost", connector.getLocalPort()).scheme(scenario.getScheme());
        List<Request> requests = new ArrayList<>();
        ContentResponse response = request
            // Listeners for future events will be notified.
            .onResponseBegin(rs1 ->
            {
                var rq1 = rs1.getRequest();
                requests.add(rq1);
                rq1.onResponseHeaders(rs2 ->
                {
                    requests.add(rs2.getRequest());
                    beginLatch.countDown();
                });
            })
            // Listeners for current or past events won't be notified.
            .onResponseHeaders(rs1 ->
            {
                requests.add(rs1.getRequest());
                rs1.getRequest().onResponseHeaders(rs2 -> headersLatch.countDown());
            })
            .send();
        requests.add(response.getRequest());

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(beginLatch.await(5, TimeUnit.SECONDS));
        assertFalse(headersLatch.await(1, TimeUnit.SECONDS));
        requests.forEach(r -> assertSame(request, r));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testConversationResponse(Scenario scenario) throws Exception
    {
        start(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                switch (org.eclipse.jetty.server.Request.getPathInContext(request))
                {
                    case "/old" -> org.eclipse.jetty.server.Response.sendRedirect(request, response, callback, "/new");
                    case "/new" ->
                    {
                        response.getHeaders().put("Special", "Value");
                        Content.Sink.write(response, true, "data", callback);
                    }
                    default -> throw new IllegalStateException();
                }
                return true;
            }
        });

        var request = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .path("/old");
        ContentResponse response = request
            .onResponseBegin(rs ->
            {
                try
                {
                    assertSame(request, rs.getRequest());
                    assertEquals(HttpStatus.OK_200, rs.getStatus());
                }
                catch (Throwable x)
                {
                    rs.abort(x);
                }
            })
            .onResponseHeaders(rs ->
            {
                try
                {
                    assertSame(request, rs.getRequest());
                    assertEquals(HttpStatus.OK_200, rs.getStatus());
                    assertTrue(rs.getHeaders().contains("Special", "Value"));
                }
                catch (Throwable x)
                {
                    rs.abort(x);
                }
            })
            .onResponseContent((rs, c) ->
            {
                try
                {
                    assertSame(request, rs.getRequest());
                    assertEquals(HttpStatus.OK_200, rs.getStatus());
                    assertTrue(rs.getHeaders().contains("Special", "Value"));
                    assertNotNull(c);
                    assertTrue(c.hasRemaining());
                }
                catch (Throwable x)
                {
                    rs.abort(x);
                }
            })
            .onResponseSuccess(rs ->
            {
                try
                {
                    assertSame(request, rs.getRequest());
                    assertEquals(HttpStatus.OK_200, rs.getStatus());
                    assertTrue(rs.getHeaders().contains("Special", "Value"));
                }
                catch (Throwable x)
                {
                    rs.abort(x);
                }
            })
            .onComplete(result ->
            {
                var rq = result.getRequest();
                var rs = result.getResponse();
                assertSame(request, rq);
                assertSame(request, rs.getRequest());
                assertEquals(HttpStatus.OK_200, rs.getStatus());
                assertTrue(rs.getHeaders().contains("Special", "Value"));
            })
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(response.getHeaders().contains("Special", "Value"));
        assertEquals("data", response.getContentAsString());
    }
}
