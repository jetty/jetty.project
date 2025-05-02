//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.management.ObjectName;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.SessionContainer;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.server.AbstractHTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HTTP2JMXTest extends AbstractJMXTest
{
    @Test
    public void testHTTP2SessionNotRegisteredAsMBean() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });

        Session session = newClientSession(new Session.Listener() {});

        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        CountDownLatch latch = new CountDownLatch(1);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                assertTrue(stream.getId() > 0);

                assertTrue(frame.isEndStream());
                assertEquals(stream.getId(), frame.getStreamId());
                assertTrue(frame.getMetaData().isResponse());
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertEquals(200, response.getStatus());

                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        // HTTP/2 sessions should be not registered as MBeans for performance reason.
        Set<ObjectName> sessionObjectNames = serverMBeanContainer.getMBeanServer().queryNames(ObjectName.getInstance("org.eclipse.jetty.http2.server.internal:*"), null);
        assertThat(sessionObjectNames, hasSize(0));
        sessionObjectNames = clientMBeanContainer.getMBeanServer().queryNames(ObjectName.getInstance("org.eclipse.jetty.http2.client.internal:*"), null);
        assertThat(sessionObjectNames, hasSize(0));

        AbstractHTTP2ServerConnectionFactory http2 = connector.getConnectionFactory(AbstractHTTP2ServerConnectionFactory.class);
        SessionContainer http2Container = http2.getBean(SessionContainer.class);
        assertThat(http2Container.getSessions(), hasSize(1));

        session.close(ErrorCode.NO_ERROR.code, "test", Callback.NOOP);

        await().atMost(5, TimeUnit.SECONDS).until(http2Container::getSessions, hasSize(0));
    }
}
