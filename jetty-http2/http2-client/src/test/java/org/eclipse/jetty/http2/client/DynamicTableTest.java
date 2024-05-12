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

package org.eclipse.jetty.http2.client;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.server.AbstractHTTP2ServerConnectionFactory;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DynamicTableTest extends AbstractTest
{
    @ParameterizedTest
    @CsvSource({"0,-1", "-1,0", "0,0"})
    public void testMaxEncoderTableCapacityZero(int clientMaxCapacity, int serverMaxCapacity) throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                resp.setStatus(200);
                resp.getOutputStream().close();
            }
        });

        if (clientMaxCapacity >= 0)
            client.setMaxEncoderTableCapacity(clientMaxCapacity);
        if (serverMaxCapacity >= 0)
            connector.getConnectionFactory(AbstractHTTP2ServerConnectionFactory.class).setMaxEncoderTableCapacity(serverMaxCapacity);

        CountDownLatch serverPreface = new CountDownLatch(1);
        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                serverPreface.countDown();
            }
        });
        assertTrue(serverPreface.await(5, TimeUnit.SECONDS));

        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        CountDownLatch latch = new CountDownLatch(1);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertEquals(200, response.getStatus());
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @CsvSource({"0,-1", "-1,0", "0,0"})
    public void testMaxDecoderTableCapacityZero(int clientMaxCapacity, int serverMaxCapacity) throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                resp.setStatus(200);
                resp.getOutputStream().close();
            }
        });

        if (clientMaxCapacity >= 0)
            client.setMaxDecoderTableCapacity(clientMaxCapacity);
        if (serverMaxCapacity >= 0)
            connector.getConnectionFactory(AbstractHTTP2ServerConnectionFactory.class).setMaxDecoderTableCapacity(serverMaxCapacity);

        CountDownLatch serverPreface = new CountDownLatch(1);
        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                serverPreface.countDown();
            }
        });
        assertTrue(serverPreface.await(5, TimeUnit.SECONDS));

        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        CountDownLatch latch = new CountDownLatch(1);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertEquals(200, response.getStatus());
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @CsvSource({"0,-1", "-1,0", "0,0"})
    public void testMaxTableCapacityZero(int clientMaxCapacity, int serverMaxCapacity) throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                resp.setStatus(200);
                resp.getOutputStream().close();
            }
        });

        if (clientMaxCapacity >= 0)
        {
            client.setMaxDecoderTableCapacity(clientMaxCapacity);
            client.setMaxEncoderTableCapacity(clientMaxCapacity);
        }
        if (serverMaxCapacity >= 0)
        {
            connector.getConnectionFactory(AbstractHTTP2ServerConnectionFactory.class).setMaxEncoderTableCapacity(serverMaxCapacity);
            connector.getConnectionFactory(AbstractHTTP2ServerConnectionFactory.class).setMaxDecoderTableCapacity(serverMaxCapacity);
        }

        CountDownLatch serverPreface = new CountDownLatch(1);
        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                serverPreface.countDown();
            }
        });
        assertTrue(serverPreface.await(5, TimeUnit.SECONDS));

        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        CountDownLatch latch = new CountDownLatch(1);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertEquals(200, response.getStatus());
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
