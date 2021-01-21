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

package org.eclipse.jetty.alpn.java.client;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Jetty;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

public class JDK9HTTP2ClientTest
{
    @Tag("external")
    @Test
    public void testJDK9HTTP2Client() throws Exception
    {
        String host = "webtide.com";
        int port = 443;

        Assumptions.assumeTrue(canConnectTo(host, port));

        HTTP2Client client = new HTTP2Client();
        try
        {
            SslContextFactory sslContextFactory = new SslContextFactory.Client();
            client.addBean(sslContextFactory);
            client.start();

            FuturePromise<Session> sessionPromise = new FuturePromise<>();
            client.connect(sslContextFactory, new InetSocketAddress(host, port), new Session.Listener.Adapter(), sessionPromise);
            Session session = sessionPromise.get(15, TimeUnit.SECONDS);

            HttpFields requestFields = new HttpFields();
            requestFields.put("User-Agent", client.getClass().getName() + "/" + Jetty.VERSION);
            MetaData.Request metaData = new MetaData.Request("GET", new HttpURI("https://" + host + ":" + port + "/"), HttpVersion.HTTP_2, requestFields);
            HeadersFrame headersFrame = new HeadersFrame(metaData, null, true);
            CountDownLatch latch = new CountDownLatch(1);
            session.newStream(headersFrame, new Promise.Adapter<>(), new Stream.Listener.Adapter()
            {
                @Override
                public void onHeaders(Stream stream, HeadersFrame frame)
                {
                    System.err.println(frame);
                    if (frame.isEndStream())
                        latch.countDown();
                }

                @Override
                public void onData(Stream stream, DataFrame frame, Callback callback)
                {
                    System.err.println(frame);
                    callback.succeeded();
                    if (frame.isEndStream())
                        latch.countDown();
                }
            });

            latch.await(15, TimeUnit.SECONDS);
        }
        finally
        {
            client.stop();
        }
    }

    private boolean canConnectTo(String host, int port)
    {
        try
        {
            new Socket(host, port).close();
            return true;
        }
        catch (Throwable x)
        {
            return false;
        }
    }
}
