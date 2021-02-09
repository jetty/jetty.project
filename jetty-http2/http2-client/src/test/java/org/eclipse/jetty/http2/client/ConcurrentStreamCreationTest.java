//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConcurrentStreamCreationTest extends AbstractTest
{
    @Test
    public void testConcurrentStreamCreation() throws Exception
    {
        int threads = 64;
        int runs = 1;
        int iterations = 1024;
        int total = threads * runs * iterations;
        CountDownLatch serverLatch = new CountDownLatch(total);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, new HttpFields());
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), response, null, true);
                stream.headers(responseFrame, Callback.NOOP);
                serverLatch.countDown();
                return null;
            }
        }, h2 -> h2.setMaxConcurrentStreams(total));

        Session session = newClient(new Session.Listener.Adapter());

        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch clientLatch = new CountDownLatch(total);
        CountDownLatch responseLatch = new CountDownLatch(runs);
        Promise<Stream> promise = new Promise.Adapter<Stream>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                clientLatch.countDown();
            }
        };
        IntStream.range(0, threads).forEach(i -> new Thread(() ->
        {
            try
            {
                barrier.await();
                IntStream.range(0, runs).forEach(j ->
                        IntStream.range(0, iterations).forEach(k ->
                        {
                            MetaData.Request request = newRequest("GET", new HttpFields());
                            HeadersFrame requestFrame = new HeadersFrame(request, null, true);
                            session.newStream(requestFrame, promise, new Stream.Listener.Adapter()
                            {
                                @Override
                                public void onHeaders(Stream stream, HeadersFrame frame)
                                {
                                    int status = ((MetaData.Response)frame.getMetaData()).getStatus();
                                    if (status == HttpStatus.OK_200 && frame.isEndStream())
                                        responseLatch.countDown();
                                }
                            });
                        }));
            }
            catch (Throwable x)
            {
                x.printStackTrace();
            }
        }).start());
        assertTrue(clientLatch.await(total, TimeUnit.MILLISECONDS), String.format("Missing streams on client: %d/%d", clientLatch.getCount(), total));
        assertTrue(serverLatch.await(total, TimeUnit.MILLISECONDS), String.format("Missing streams on server: %d/%d", serverLatch.getCount(), total));
        assertTrue(responseLatch.await(total, TimeUnit.MILLISECONDS), String.format("Missing response on client: %d/%d", clientLatch.getCount(), total));
    }
}
