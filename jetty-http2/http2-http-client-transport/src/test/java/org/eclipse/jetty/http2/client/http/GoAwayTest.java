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

package org.eclipse.jetty.http2.client.http;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.AbstractConnectionPool;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.RandomConnectionPool;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.ISession;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GoAwayTest extends AbstractTest
{
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testConnectionIsRemovedFromPoolOnGracefulGoAwayReceived(boolean graceful) throws Exception
    {
        long timeout = 5000;
        AtomicReference<Response> responseRef = new AtomicReference<>();
        CountDownLatch responseLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            private Stream goAwayStream;

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Request request = (MetaData.Request)frame.getMetaData();
                String path = request.getURI().getPath();

                if ("/prime".equals(path))
                {
                    respond(stream);
                }
                else if ("/goaway".equals(path))
                {
                    try
                    {
                        goAwayStream = stream;

                        if (graceful)
                        {
                            // Send to the client a graceful GOAWAY.
                            ((ISession)stream.getSession()).shutdown();
                        }
                        else
                        {
                            // Send to the client a non-graceful GOAWAY.
                            stream.getSession().close(ErrorCode.ENHANCE_YOUR_CALM_ERROR.code, null, Callback.NOOP);
                        }

                        // Wait for the client to receive the GOAWAY.
                        Thread.sleep(1000);

                        // This request will be performed on a different connection.
                        client.newRequest("localhost", connector.getLocalPort())
                            .path("/after")
                            .timeout(timeout / 2, TimeUnit.MILLISECONDS)
                            .send(result ->
                            {
                                responseRef.set(result.getResponse());
                                responseLatch.countDown();
                            });
                    }
                    catch (Exception x)
                    {
                        throw new RuntimeException(x);
                    }
                }
                else if ("/after".equals(path))
                {
                    // Wait for the /after request to arrive to the server
                    // before answering to the /goaway request.
                    // The /goaway request must succeed because it's in
                    // flight and seen by the server when the GOAWAY happens,
                    // so it will be completed before closing the connection.
                    respond(goAwayStream);
                    respond(stream);
                }
                return null;
            }

            private void respond(Stream stream)
            {
                long remotePort = ((InetSocketAddress)stream.getSession().getRemoteSocketAddress()).getPort();
                HttpFields responseHeaders = HttpFields.build().putLongField("X-Remote-Port", remotePort);
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, responseHeaders);
                stream.headers(new HeadersFrame(stream.getId(), response, null, true));
            }
        });

        Response response = client.newRequest("localhost", connector.getLocalPort())
            .path("/prime")
            .timeout(timeout, TimeUnit.MILLISECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        long primePort = response.getHeaders().getLongField("X-Remote-Port");

        response = client.newRequest("localhost", connector.getLocalPort())
            .path("/goaway")
            .timeout(timeout, TimeUnit.MILLISECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        long goAwayPort = response.getHeaders().getLongField("X-Remote-Port");
        assertEquals(primePort, goAwayPort);

        assertTrue(responseLatch.await(timeout, TimeUnit.MILLISECONDS));
        response = responseRef.get();
        assertNotNull(response);
        assertEquals(HttpStatus.OK_200, response.getStatus());
        // The /after request must happen on a different port
        // because the first connection has been removed from the pool.
        long afterPort = response.getHeaders().getLongField("X-Remote-Port");
        assertNotEquals(primePort, afterPort);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    public void testImmediateGoAwayAndDisconnectDoesNotLeakClientConnections(int maxConnections) throws Exception
    {
        int maxConcurrent = 64;
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                return Map.of(SettingsFrame.MAX_CONCURRENT_STREAMS, maxConcurrent);
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                HTTP2Session session = (HTTP2Session)stream.getSession();
                session.goAway(new GoAwayFrame(Integer.MAX_VALUE, ErrorCode.INTERNAL_ERROR.code, "problem_reproduced".getBytes(StandardCharsets.UTF_8)),
                    Callback.from(session::disconnect));
                return null;
            }
        });
        client.getTransport().setConnectionPoolFactory(destination -> new RandomConnectionPool(destination, maxConnections, destination, 1));

        // Use enough requests to trigger the maximum use
        // of the pool, plus some that will remain queued.
        int requestCount = maxConcurrent * maxConnections + 10;
        CountDownLatch latch = new CountDownLatch(requestCount);
        for (int i = 0; i < requestCount; i++)
        {
            client.newRequest("localhost", connector.getLocalPort())
                .path("/" + i)
                .send(result -> latch.countDown());
        }

        // All the requests should fail, since the server is always closing the connection.
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        List<Destination> destinations = client.getDestinations();
        assertThat(destinations.size(), equalTo(1));

        AbstractConnectionPool pool = (AbstractConnectionPool)((HttpDestination)destinations.get(0)).getConnectionPool();
        String dump = pool.dump();
        assertFalse(dump.lines().anyMatch(l -> l.matches(".*multiplex=[1-9]([0-9]+)?.*")), dump);
    }
}
