//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.spdy.server.http;

import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.http.HTTPSPDYHeader;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.hamcrest.CoreMatchers;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

@Ignore("So far only used for testing performance tweaks. So no need to run it in a build")
public class SimpleHTTPBenchmarkTest extends AbstractHTTPSPDYTest
{
    private static final Logger LOG = Log.getLogger(SimpleHTTPBenchmarkTest.class);
    private final int dataSize = 4096 * 100;
    private Session session;
    private int requestCount = 100;

    public SimpleHTTPBenchmarkTest(short version)
    {
        super(version);
    }

    @Before
    public void setUp() throws Exception
    {
        final byte[] data = new byte[dataSize];
        new Random().nextBytes(data);
        session = startClient(version, startHTTPServer(version, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                    throws IOException, ServletException
            {
                request.setHandled(true);
                assertEquals("GET", httpRequest.getMethod());
                assertThat("accept-encoding is set to gzip, even if client didn't set it",
                        httpRequest.getHeader("accept-encoding"), containsString("gzip"));
                assertThat(httpRequest.getHeader("host"), is("localhost:" + connector.getLocalPort()));
                httpResponse.getOutputStream().write(data);
            }
        }, 0), null);
    }

    @Test
    public void testRunBenchmark() throws Exception
    {
        long overallStart = System.nanoTime();
        int iterations = 20;
        for (int j = 0; j < iterations; j++)
        {
            long start = System.nanoTime();
            for (int i = 0; i < requestCount; i++)
                sendGetRequestWithData();
            long timeElapsed = System.nanoTime() - start;
            LOG.info("Requests with {}b response took: {}ms", dataSize, timeElapsed / 1000 / 1000);
        }
        long timeElapsedOverall = (System.nanoTime() - overallStart) / 1000 / 1000;
        LOG.info("Time elapsed overall: {}ms avg: {}ms", timeElapsedOverall, timeElapsedOverall / iterations);
    }

    private void sendGetRequest() throws Exception
    {
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final String path = "/foo";

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getLocalPort(), version, "GET", path);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                assertTrue(replyInfo.isClose());
                Fields replyHeaders = replyInfo.getHeaders();
                assertThat(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("200"), CoreMatchers.is(true));
                assertThat(replyHeaders.get(HttpHeader.SERVER.asString()), CoreMatchers.is(notNullValue()));
                assertThat(replyHeaders.get(HttpHeader.X_POWERED_BY.asString()), CoreMatchers.is(notNullValue()));
                replyLatch.countDown();
            }
        });

        assertThat("reply has been received", replyLatch.await(5, TimeUnit.SECONDS), is(true));
    }

    private void sendGetRequestWithData() throws Exception
    {
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        final String path = "/foo";

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getLocalPort(), version, "GET", path);
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Fields replyHeaders = replyInfo.getHeaders();
                assertThat(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).getValue().contains("200"), CoreMatchers.is(true));
                assertThat(replyHeaders.get(HttpHeader.SERVER.asString()), CoreMatchers.is(notNullValue()));
                assertThat(replyHeaders.get(HttpHeader.X_POWERED_BY.asString()), CoreMatchers.is(notNullValue()));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataInfo.consume(dataInfo.available());
                if (dataInfo.isClose())
                    dataLatch.countDown();
            }
        });

        assertThat("reply has been received", replyLatch.await(5, TimeUnit.SECONDS), is(true));
        assertThat("data has been received", dataLatch.await(5, TimeUnit.SECONDS), is(true));
    }
}
