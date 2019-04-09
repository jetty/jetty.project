//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2.client;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConnectTunnelTest extends AbstractTest
{
    @Test
    public void testCONNECT() throws Exception
    {
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                // Verifies that the CONNECT request is well formed.
                MetaData.Request request = (MetaData.Request)frame.getMetaData();
                assertEquals(HttpMethod.CONNECT.asString(), request.getMethod());
                HttpURI uri = request.getURI();
                assertNull(uri.getScheme());
                assertNull(uri.getPath());
                assertNotNull(uri.getAuthority());
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        stream.data(frame, callback);
                    }
                };
            }
        });

        Session client = newClient(new Session.Listener.Adapter());

        CountDownLatch latch = new CountDownLatch(1);
        byte[] bytes = "HELLO".getBytes(StandardCharsets.UTF_8);
        String host = "localhost";
        int port = connector.getLocalPort();
        String authority = host + ":" + port;
        MetaData.Request request = new MetaData.Request(HttpMethod.CONNECT.asString(), null, new HostPortHttpField(authority), null, HttpVersion.HTTP_2, new HttpFields());
        FuturePromise<Stream> streamPromise = new FuturePromise<>();
        client.newStream(new HeadersFrame(request, null, false), streamPromise, new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                if (frame.isEndStream())
                    latch.countDown();
            }
        });
        Stream stream = streamPromise.get(5, TimeUnit.SECONDS);
        ByteBuffer data = ByteBuffer.wrap(bytes);
        stream.data(new DataFrame(stream.getId(), data, true), Callback.NOOP);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
