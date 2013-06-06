//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.DispatcherType;

import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlets.GzipFilter;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.Assert;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

public class WriteThroughAggregateBufferTest extends AbstractHTTPSPDYTest
{
    private static final Logger LOG = Log.getLogger(WriteThroughAggregateBufferTest.class);
    public WriteThroughAggregateBufferTest(short version)
    {
        super(version);
    }

    /**
     * This test was created due to bugzilla #409403. It tests a specific code path through DefaultServlet leading to
     * every byte of the response being sent one by one through BufferUtil.writeTo(). To do this,
     * we need to use DefaultServlet + ResourceCache + GzipFilter. As this bug only affected SPDY,
     * we test using SPDY. The accept-encoding header must not be set to replicate this issue.
     * @throws Exception
     */
    @Test
    public void testGetBigJavaScript() throws Exception
    {
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);

        ServletContextHandler contextHandler = new ServletContextHandler(getServer(), "/ctx");

        FilterHolder gzipFilterHolder = new FilterHolder(GzipFilter.class);
        contextHandler.addFilter(gzipFilterHolder, "/*", EnumSet.allOf(DispatcherType.class));

        ServletHolder servletHolder = new ServletHolder(DefaultServlet.class);
        servletHolder.setInitParameter("resourceBase", "src/test/resources/");
        servletHolder.setInitParameter("dirAllowed", "true");
        servletHolder.setInitParameter("maxCachedFiles", "10");
        contextHandler.addServlet(servletHolder, "/*");
        Session session = startClient(version, startHTTPServer(version, contextHandler), null);

        Fields headers = SPDYTestUtils.createHeaders("localhost", connector.getPort(), version, "GET",
                "/ctx/big_script.js");
//        headers.add("accept-encoding","gzip");
        session.syn(new SynInfo(headers, true), new StreamFrameListener.Adapter()
        {
            AtomicInteger bytesReceived= new AtomicInteger(0);
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                Fields replyHeaders = replyInfo.getHeaders();
                Assert.assertTrue(replyHeaders.get(HTTPSPDYHeader.STATUS.name(version)).value().contains("200"));
                replyLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                bytesReceived.addAndGet(dataInfo.available());
                dataInfo.consume(dataInfo.available());
                if (dataInfo.isClose())
                {
                    assertThat("bytes received matches file size: 76847", bytesReceived.get(), is(76847));
                    dataLatch.countDown();
                }
            }

        });

        assertThat("reply is received", replyLatch.await(5, TimeUnit.SECONDS), is(true));
        assertThat("all data is sent", dataLatch.await(5, TimeUnit.SECONDS), is(true));
    }
}
