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

package org.eclipse.jetty.http2.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.stream.IntStream;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.internal.ErrorCode;
import org.eclipse.jetty.http2.internal.HTTP2Session;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MaxPushedStreamsTest extends AbstractTest
{
    @Test
    public void testMaxPushedStreams() throws Exception
    {
        int maxPushed = 2;

        CountDownLatch resetLatch = new CountDownLatch(1);
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                // Trick the server into thinking it can push unlimited streams.
                ((HTTP2Session)stream.getSession()).setMaxLocalStreams(-1);

                BiFunction<List<Stream>, Stream, List<Stream>> add = (l, s) ->
                {
                    l.add(s);
                    return l;
                };
                BinaryOperator<List<Stream>> addAll = (l1, l2) ->
                {
                    l1.addAll(l2);
                    return l1;
                };
                CompletableFuture<List<Stream>> result = CompletableFuture.completedFuture(new ArrayList<>());
                // Push maxPushed resources...
                IntStream.range(0, maxPushed)
                    .mapToObj(i -> new PushPromiseFrame(stream.getId(), 0, newRequest("GET", "/push_" + i, HttpFields.EMPTY)))
                    .map(pushFrame ->
                    {
                        Promise.Completable<Stream> promise = new Promise.Completable<>();
                        stream.push(pushFrame, promise, null);
                        return promise;
                    })
                    // ... wait for the pushed streams...
                    .reduce(result, (cfList, cfStream) -> cfList.thenCombine(cfStream, add),
                        (cfList1, cfList2) -> cfList1.thenCombine(cfList2, addAll))
                    // ... then push one extra stream, the client must reject it...
                    .thenApply(streams ->
                    {
                        PushPromiseFrame extraPushFrame = new PushPromiseFrame(stream.getId(), 0, newRequest("GET", "/push_extra", HttpFields.EMPTY));
                        FuturePromise<Stream> extraPromise = new FuturePromise<>();
                        stream.push(extraPushFrame, extraPromise, new Stream.Listener()
                        {
                            @Override
                            public void onReset(Stream stream, ResetFrame frame, Callback callback)
                            {
                                assertEquals(ErrorCode.REFUSED_STREAM_ERROR.code, frame.getError());
                                resetLatch.countDown();
                                callback.succeeded();
                            }
                        });
                        return streams;
                    })
                    // ... then send the data for the valid pushed streams...
                    .thenAccept(streams -> streams.forEach(pushedStream ->
                    {
                        DataFrame data = new DataFrame(pushedStream.getId(), BufferUtil.EMPTY_BUFFER, true);
                        pushedStream.data(data, Callback.NOOP);
                    }))
                    // ... then send the response.
                    .thenRun(() ->
                    {
                        MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
                        stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                    });
                return null;
            }
        });
        http2Client.setMaxConcurrentPushedStreams(maxPushed);

        Session session = newClientSession(new Session.Listener() {});
        MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
        CountDownLatch responseLatch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(request, null, true), new Promise.Adapter<>(), new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                    responseLatch.countDown();
            }
        });

        assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }
}
