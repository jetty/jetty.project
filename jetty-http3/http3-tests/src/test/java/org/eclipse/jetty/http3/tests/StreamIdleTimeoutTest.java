//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.tests;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.server.AbstractHTTP3ServerConnectionFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StreamIdleTimeoutTest extends AbstractHTTP3ClientServerTest
{
    @Test
    public void testClientStreamIdleTimeout() throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        startServer(new Session.Server.Listener()
        {
            @Override
            public Stream.Listener onRequest(Stream stream, HeadersFrame frame)
            {
                MetaData.Request request = (MetaData.Request)frame.getMetaData();
                if ("/idle".equals(request.getURI().getPath()))
                {
                    assertFalse(frame.isLast());
                    stream.demand();
                    return new Stream.Listener()
                    {
                        @Override
                        public void onDataAvailable(Stream stream)
                        {
                            // When the client closes the stream, the server
                            // may either receive an empty, last, DATA frame, or
                            // an exception because the stream has been reset.
                            try
                            {
                                Stream.Data data = stream.readData();
                                if (data != null)
                                {
                                    assertTrue(data.isLast());
                                    assertEquals(0, data.getByteBuffer().remaining());
                                    serverLatch.countDown();
                                }
                                else
                                {
                                    stream.demand();
                                }
                            }
                            catch (Exception x)
                            {
                                serverLatch.countDown();
                                throw x;
                            }
                        }
                    };
                }
                else
                {
                    MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY);
                    stream.respond(new HeadersFrame(response, true));
                    return null;
                }
            }
        });
        startClient();

        long streamIdleTimeout = 1000;
        client.setStreamIdleTimeout(streamIdleTimeout);

        Session.Client session = client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Client.Listener() {})
            .get(5, TimeUnit.SECONDS);

        CountDownLatch clientIdleLatch = new CountDownLatch(1);
        HttpURI uri1 = HttpURI.from("https://localhost:" + connector.getLocalPort() + "/idle");
        MetaData.Request request1 = new MetaData.Request(HttpMethod.GET.asString(), uri1, HttpVersion.HTTP_3, HttpFields.EMPTY);
        session.newRequest(new HeadersFrame(request1, false), new Stream.Listener()
        {
            @Override
            public boolean onIdleTimeout(Stream stream, Throwable failure)
            {
                clientIdleLatch.countDown();
                // Signal to close the stream.
                return true;
            }
        }).get(5, TimeUnit.SECONDS);

        // The server does not reply, the client must idle timeout.
        assertTrue(clientIdleLatch.await(2 * streamIdleTimeout, TimeUnit.MILLISECONDS));
        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));

        // The session should still be open, verify by sending another request.
        CountDownLatch clientLatch = new CountDownLatch(1);
        HttpURI uri2 = HttpURI.from("https://localhost:" + connector.getLocalPort() + "/");
        MetaData.Request request2 = new MetaData.Request(HttpMethod.GET.asString(), uri2, HttpVersion.HTTP_3, HttpFields.EMPTY);
        session.newRequest(new HeadersFrame(request2, true), new Stream.Listener()
        {
            @Override
            public void onResponse(Stream stream, HeadersFrame frame)
            {
                clientLatch.countDown();
            }
        });

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerStreamIdleTimeout() throws Exception
    {
        long idleTimeout = 1000;
        CountDownLatch serverIdleLatch = new CountDownLatch(1);
        startServer(new Session.Server.Listener()
        {
            @Override
            public Stream.Listener onRequest(Stream stream, HeadersFrame frame)
            {
                MetaData.Request request = (MetaData.Request)frame.getMetaData();
                if ("/idle".equals(request.getURI().getPath()))
                {
                    return new Stream.Listener()
                    {
                        @Override
                        public boolean onIdleTimeout(Stream stream, Throwable failure)
                        {
                            serverIdleLatch.countDown();
                            return true;
                        }
                    };
                }
                else
                {
                    MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY);
                    stream.respond(new HeadersFrame(response, true));
                    return null;
                }
            }
        });
        AbstractHTTP3ServerConnectionFactory h3 = server.getConnectors()[0].getConnectionFactory(AbstractHTTP3ServerConnectionFactory.class);
        assertNotNull(h3);
        h3.setStreamIdleTimeout(idleTimeout);
        startClient();

        Session.Client session = client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Client.Listener() {})
            .get(5, TimeUnit.SECONDS);

        CountDownLatch clientFailureLatch = new CountDownLatch(1);
        HttpURI uri1 = HttpURI.from("https://localhost:" + connector.getLocalPort() + "/idle");
        MetaData.Request request1 = new MetaData.Request(HttpMethod.GET.asString(), uri1, HttpVersion.HTTP_3, HttpFields.EMPTY);
        session.newRequest(new HeadersFrame(request1, false), new Stream.Listener()
        {
            @Override
            public void onFailure(long error, Throwable failure)
            {
                // The server idle times out, but did not send any data back.
                // However, the stream is readable, but an attempt to read it
                // will cause an exception that is notified here.
                clientFailureLatch.countDown();
            }
        });

        assertTrue(serverIdleLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        assertTrue(clientFailureLatch.await(5, TimeUnit.SECONDS));

        // The session should still be open, verify by sending another request.
        CountDownLatch clientLatch = new CountDownLatch(1);
        HttpURI uri2 = HttpURI.from("https://localhost:" + connector.getLocalPort() + "/");
        MetaData.Request request2 = new MetaData.Request(HttpMethod.GET.asString(), uri2, HttpVersion.HTTP_3, HttpFields.EMPTY);
        session.newRequest(new HeadersFrame(request2, true), new Stream.Listener()
        {
            @Override
            public void onResponse(Stream stream, HeadersFrame frame)
            {
                clientLatch.countDown();
            }
        });

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }
}
