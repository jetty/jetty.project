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
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisabledOnOs(value = OS.WINDOWS, disabledReason = "Unable to create all of the streams")
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
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
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
                            MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
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
