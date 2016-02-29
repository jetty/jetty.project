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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.junit.Assert;
import org.junit.Test;

public class StreamCountTest extends AbstractTest
{
    @Test
    public void testServerAllowsOneStreamEnforcedByClient() throws Exception
    {
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                Map<Integer, Integer> settings = new HashMap<>();
                settings.put(SettingsFrame.MAX_CONCURRENT_STREAMS, 1);
                return settings;
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        if (frame.isEndStream())
                        {
                            HttpFields fields = new HttpFields();
                            MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, fields);
                            stream.headers(new HeadersFrame(stream.getId(), metaData, null, true), callback);
                        }
                        else
                        {
                            callback.succeeded();
                        }
                    }
                };
            }
        });

        final CountDownLatch settingsLatch = new CountDownLatch(1);
        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }
        });

        Assert.assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        HttpFields fields = new HttpFields();
        MetaData.Request metaData = newRequest("GET", fields);
        HeadersFrame frame1 = new HeadersFrame(metaData, null, false);
        FuturePromise<Stream> streamPromise1 = new FuturePromise<>();
        final CountDownLatch responseLatch = new CountDownLatch(1);
        session.newStream(frame1, streamPromise1, new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                    responseLatch.countDown();
            }
        });
        Stream stream1 = streamPromise1.get(5, TimeUnit.SECONDS);

        HeadersFrame frame2 = new HeadersFrame(metaData, null, false);
        FuturePromise<Stream> streamPromise2 = new FuturePromise<>();
        session.newStream(frame2, streamPromise2, new Stream.Listener.Adapter());

        try
        {
            streamPromise2.get(5, TimeUnit.SECONDS);
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            // Expected
        }

        stream1.data(new DataFrame(stream1.getId(), BufferUtil.EMPTY_BUFFER, true), Callback.NOOP);
        Assert.assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerAllowsOneStreamEnforcedByServer() throws Exception
    {
        final CountDownLatch resetLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                HTTP2Session session = (HTTP2Session)stream.getSession();
                session.setMaxRemoteStreams(1);

                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        if (frame.isEndStream())
                        {
                            HttpFields fields = new HttpFields();
                            MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, fields);
                            stream.headers(new HeadersFrame(stream.getId(), metaData, null, true), callback);
                        }
                        else
                        {
                            callback.succeeded();
                        }
                    }
                };
            }
        });

        Session session = newClient(new Session.Listener.Adapter());

        HttpFields fields = new HttpFields();
        MetaData.Request metaData = newRequest("GET", fields);
        HeadersFrame frame1 = new HeadersFrame(metaData, null, false);
        FuturePromise<Stream> streamPromise1 = new FuturePromise<>();
        final CountDownLatch responseLatch = new CountDownLatch(1);
        session.newStream(frame1, streamPromise1, new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                    responseLatch.countDown();
            }
        });

        Stream stream1 = streamPromise1.get(5, TimeUnit.SECONDS);

        HeadersFrame frame2 = new HeadersFrame(metaData, null, false);
        FuturePromise<Stream> streamPromise2 = new FuturePromise<>();
        session.newStream(frame2, streamPromise2, new Stream.Listener.Adapter()
        {
            @Override
            public void onReset(Stream stream, ResetFrame frame)
            {
                resetLatch.countDown();
            }
        });

        streamPromise2.get(5, TimeUnit.SECONDS);
        Assert.assertTrue(resetLatch.await(5, TimeUnit.SECONDS));

        stream1.data(new DataFrame(stream1.getId(), BufferUtil.EMPTY_BUFFER, true), Callback.NOOP);
        Assert.assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }
}
