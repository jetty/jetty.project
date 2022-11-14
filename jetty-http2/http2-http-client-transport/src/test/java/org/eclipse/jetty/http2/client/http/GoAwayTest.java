//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.http2.client.http;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                            ((HTTP2Session)stream.getSession()).goAway(GoAwayFrame.GRACEFUL, Callback.NOOP);
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
                HTTP2Session session = (HTTP2Session)stream.getSession();
                long remotePort = session.getEndPoint().getRemoteAddress().getPort();
                HttpFields responseHeaders = new HttpFields();
                responseHeaders.putLongField("X-Remote-Port", remotePort);
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, responseHeaders);
                stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
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
}
