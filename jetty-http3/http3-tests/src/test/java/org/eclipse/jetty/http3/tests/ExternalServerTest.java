//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.tests;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.util.HostPort;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Disabled
public class ExternalServerTest
{
    @Test
    @Tag("external")
    public void testExternalServer() throws Exception
    {
        HTTP3Client client = new HTTP3Client();
        client.start();
        try
        {
//            HostPort hostPort = new HostPort("nghttp2.org:4433");
            HostPort hostPort = new HostPort("quic.tech:8443");
//            HostPort hostPort = new HostPort("h2o.examp1e.net:443");
//            HostPort hostPort = new HostPort("test.privateoctopus.com:4433");
            Session.Client session = client.connect(new InetSocketAddress(hostPort.getHost(), hostPort.getPort()), new Session.Client.Listener() {})
                .get(5, TimeUnit.SECONDS);

            CountDownLatch requestLatch = new CountDownLatch(1);
            HttpURI uri = HttpURI.from(String.format("https://%s/", hostPort));
            MetaData.Request request = new MetaData.Request(HttpMethod.GET.asString(), uri, HttpVersion.HTTP_3, HttpFields.EMPTY);
            session.newRequest(new HeadersFrame(request, true), new Stream.Client.Listener()
            {
                @Override
                public void onResponse(Stream.Client stream, HeadersFrame frame)
                {
                    System.err.println("RESPONSE HEADER = " + frame);
                    if (frame.isLast())
                    {
                        requestLatch.countDown();
                        return;
                    }
                    stream.demand();
                }

                @Override
                public void onDataAvailable(Stream.Client stream)
                {
                    Stream.Data data = stream.readData();
                    System.err.println("RESPONSE DATA = " + data);
                    if (data != null)
                    {
                        data.complete();
                        if (data.isLast())
                        {
                            requestLatch.countDown();
                            return;
                        }
                    }
                    stream.demand();
                }

                @Override
                public void onTrailer(Stream.Client stream, HeadersFrame frame)
                {
                    System.err.println("RESPONSE TRAILER = " + frame);
                    requestLatch.countDown();
                }
            });

            assertTrue(requestLatch.await(5, TimeUnit.SECONDS));
        }
        finally
        {
            client.stop();
        }
    }
}
