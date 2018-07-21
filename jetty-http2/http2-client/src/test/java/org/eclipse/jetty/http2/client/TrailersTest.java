//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.StringUtil;
import org.hamcrest.Matchers;
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
    public void testServletRequestTrailers() throws Exception
    {
        CountDownLatch trailerLatch = new CountDownLatch(1);
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                Request jettyRequest = (Request)request;
                // No trailers yet.
                Assert.assertNull(jettyRequest.getTrailers());

                trailerLatch.countDown();

                // Read the content.
                ServletInputStream input = jettyRequest.getInputStream();
                while (true)
                {
                    int read = input.read();
                    if (read < 0)
                        break;
                }

                // Now we have the trailers.
                HttpFields trailers = jettyRequest.getTrailers();
                Assert.assertNotNull(trailers);
                Assert.assertNotNull(trailers.get("X-Trailer"));
            }
        });

        Session session = newClient(new Session.Listener.Adapter());

        HttpFields requestFields = new HttpFields();
        requestFields.put("X-Request", "true");
        MetaData.Request request = newRequest("GET", requestFields);
        HeadersFrame requestFrame = new HeadersFrame(request, null, false);
        FuturePromise<Stream> streamPromise = new FuturePromise<>();
        CountDownLatch latch = new CountDownLatch(1);
        session.newStream(requestFrame, streamPromise, new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
                if (frame.isEndStream())
                    latch.countDown();
            }
        });
        Stream stream = streamPromise.get(5, TimeUnit.SECONDS);

        // Send some data.
        Callback.Completable callback = new Callback.Completable();
        stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(16), false), callback);

        Assert.assertTrue(trailerLatch.await(5, TimeUnit.SECONDS));

        // Send the trailers.
        callback.thenRun(() ->
        {
            HttpFields trailerFields = new HttpFields();
            trailerFields.put("X-Trailer", "true");
            MetaData trailers = new MetaData(HttpVersion.HTTP_2, trailerFields);
            HeadersFrame trailerFrame = new HeadersFrame(stream.getId(), trailers, null, true);
            stream.headers(trailerFrame, Callback.NOOP);
        });

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

    @Test
    public void testTrailersSentByServerShouldNotSendEmptyDataFrame() throws Exception
    {
        String trailerName = "X-Trailer";
        String trailerValue = "Zot!";
        start(new EmptyHttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                Request jettyRequest = (Request)request;
                Response jettyResponse = jettyRequest.getResponse();
                HttpFields trailers = new HttpFields();
                jettyResponse.setTrailers(() -> trailers);

                jettyResponse.getOutputStream().write("hello_trailers".getBytes(StandardCharsets.UTF_8));
                jettyResponse.flushBuffer();
                // Force the content to be sent above, and then only send the trailers below.
                trailers.put(trailerName, trailerValue);
            }
        });

        Session session = newClient(new Session.Listener.Adapter());
        MetaData.Request request = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame = new HeadersFrame(request, null, true);
        CountDownLatch latch = new CountDownLatch(1);
        List<Frame> frames = new ArrayList<>();
        session.newStream(requestFrame, new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                frames.add(frame);
                if (frame.isEndStream())
                    latch.countDown();
            }

            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                frames.add(frame);
                callback.succeeded();
            }
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assert.assertThat(frames.toString(), frames.size(), Matchers.is(3));

        HeadersFrame headers = (HeadersFrame)frames.get(0);
        DataFrame data = (DataFrame)frames.get(1);
        HeadersFrame trailers = (HeadersFrame)frames.get(2);

        Assert.assertFalse(headers.isEndStream());
        Assert.assertFalse(data.isEndStream());
        Assert.assertTrue(trailers.isEndStream());
        Assert.assertThat(trailers.getMetaData().getFields().get(trailerName), Matchers.equalTo(trailerValue));
    }

    @Test
    public void testRequestTrailerInvalidHpack() throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                try
                {
                    // Read the content to read the trailers
                    ServletInputStream input = request.getInputStream();
                    while (true)
                    {
                        int read = input.read();
                        if (read < 0)
                            break;
                    }
                }
                catch (IOException x)
                {
                    serverLatch.countDown();
                    throw x;
                }
            }
        });

        CountDownLatch clientLatch = new CountDownLatch(1);
        Session session = newClient(new Session.Listener.Adapter());
        MetaData.Request request = newRequest("POST", new HttpFields());
        HeadersFrame requestFrame = new HeadersFrame(request, null, false);
        FuturePromise<Stream> promise = new FuturePromise<>();
        session.newStream(requestFrame, promise, new Stream.Listener.Adapter()
        {
            @Override
            public void onReset(Stream stream, ResetFrame frame)
            {
                clientLatch.countDown();
            }
        });
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        ByteBuffer data = ByteBuffer.wrap(StringUtil.getUtf8Bytes("hello"));
        Callback.Completable completable = new Callback.Completable();
        stream.data(new DataFrame(stream.getId(), data, false), completable);
        completable.thenRun(() ->
        {
            // Invalid trailer: cannot contain pseudo headers.
            HttpFields trailerFields = new HttpFields();
            trailerFields.put(HttpHeader.C_METHOD, "GET");
            MetaData trailer = new MetaData(HttpVersion.HTTP_2, trailerFields);
            HeadersFrame trailerFrame = new HeadersFrame(stream.getId(), trailer, null, true);
            stream.headers(trailerFrame, Callback.NOOP);
        });

        Assert.assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }
}
