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
import org.eclipse.jetty.http2.frames.HeadersFrame;
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
            client.connect(sslContextFactory, new InetSocketAddress(host, port), new Session.Listener() {}, sessionPromise);
            Session session = sessionPromise.get(15, TimeUnit.SECONDS);

            HttpFields.Mutable requestFields = HttpFields.build();
            requestFields.put("User-Agent", client.getClass().getName() + "/" + Jetty.VERSION);
            MetaData.Request metaData = new MetaData.Request("GET", HttpURI.from("https://" + host + ":" + port + "/"), HttpVersion.HTTP_2, requestFields);
            HeadersFrame headersFrame = new HeadersFrame(metaData, null, true);
            CountDownLatch latch = new CountDownLatch(1);
            session.newStream(headersFrame, new Promise.Adapter<>(), new Stream.Listener()
            {
                @Override
                public void onHeaders(Stream stream, HeadersFrame frame)
                {
                    System.err.println(frame);
                    if (frame.isEndStream())
                        latch.countDown();
                    stream.demand();
                }

                @Override
                public void onDataAvailable(Stream stream)
                {
                    Stream.Data data = stream.readData();
                    System.err.println(data);
                    data.release();
                    if (data.frame().isEndStream())
                        latch.countDown();
                    else
                        stream.demand();
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
