//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.junit.Assert;
import org.junit.Test;

public class TrailersTest extends AbstractTest
{
    @Test
    public void testTrailersSentByClient() throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Request request = (MetaData.Request)frame.getMetaData();
                Assert.assertFalse(frame.isEndStream());
                Assert.assertTrue(request.getFields().containsKey("X-Request"));
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onHeaders(Stream stream, HeadersFrame frame)
                    {
                        MetaData trailer = frame.getMetaData();
                        Assert.assertTrue(frame.isEndStream());
                        Assert.assertTrue(trailer.getFields().containsKey("X-Trailer"));
                        latch.countDown();
                    }
                };
            }
        });

        Session session = newClient(new Session.Listener.Adapter());

        HttpFields requestFields = new HttpFields();
        requestFields.put("X-Request", "true");
        MetaData.Request request = newRequest("GET", requestFields);
        HeadersFrame requestFrame = new HeadersFrame(request, null, false);
        FuturePromise<Stream> streamPromise = new FuturePromise<>();
        session.newStream(requestFrame, streamPromise, new Stream.Listener.Adapter());
        Stream stream = streamPromise.get(5, TimeUnit.SECONDS);

        // Send the trailers.
        HttpFields trailerFields = new HttpFields();
        trailerFields.put("X-Trailer", "true");
        MetaData trailers = new MetaData(HttpVersion.HTTP_2, trailerFields);
        HeadersFrame trailerFrame = new HeadersFrame(stream.getId(), trailers, null, true);
        stream.headers(trailerFrame, Callback.NOOP);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testTrailersSentByServer() throws Exception
    {
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                HttpFields responseFields = new HttpFields();
                responseFields.put("X-Response", "true");
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, responseFields);
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), response, null, false);
                stream.headers(responseFrame, new Callback()
                {
                    @Override
                    public void succeeded()
                    {
                        HttpFields trailerFields = new HttpFields();
                        trailerFields.put("X-Trailer", "true");
                        MetaData trailer = new MetaData(HttpVersion.HTTP_2, trailerFields);
                        HeadersFrame trailerFrame = new HeadersFrame(stream.getId(), trailer, null, true);
                        stream.headers(trailerFrame, NOOP);
                    }
                });
                return null;
            }
        });

        Session session = newClient(new Session.Listener.Adapter());
        MetaData.Request request = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame = new HeadersFrame(request, null, true);
        CountDownLatch latch = new CountDownLatch(1);
        session.newStream(requestFrame, new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            private boolean responded;

            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (!responded)
                {
                    MetaData.Response response = (MetaData.Response)frame.getMetaData();
                    Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
                    Assert.assertTrue(response.getFields().containsKey("X-Response"));
                    Assert.assertFalse(frame.isEndStream());
                    responded = true;
                }
                else
                {
                    MetaData trailer = frame.getMetaData();
                    Assert.assertTrue(trailer.getFields().containsKey("X-Trailer"));
                    Assert.assertTrue(frame.isEndStream());
                    latch.countDown();
                }
            }
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
