//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.junit.Assert;
import org.junit.Test;

public class PrefaceTest extends AbstractTest
{
    @Test
    public void testServerPrefaceBeforeClientPreface() throws Exception
    {
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public void onAccept(Session session)
            {
                // Send the server preface from here.
                session.settings(new SettingsFrame(new HashMap<>(), false), Callback.NOOP);
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, new HttpFields());
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, true);
                stream.headers(responseFrame, Callback.NOOP);
                return null;
            }
        });

        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                try
                {
                    // Wait for the server preface (a SETTINGS frame) to
                    // arrive on the client, and for its reply to be sent.
                    Thread.sleep(1000);
                    return null;
                }
                catch (InterruptedException x)
                {
                    x.printStackTrace();
                    return null;
                }
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        MetaData.Request metaData = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, true);
        session.newStream(requestFrame, new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                    latch.countDown();
            }
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
