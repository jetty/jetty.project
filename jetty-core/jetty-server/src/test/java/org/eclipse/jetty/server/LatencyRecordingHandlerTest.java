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

package org.eclipse.jetty.server;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.eclipse.jetty.logging.JettyLevel;
import org.eclipse.jetty.logging.JettyLogger;
import org.eclipse.jetty.server.handler.AbstractLatencyRecordingHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

public class LatencyRecordingHandlerTest
{
    private JettyLevel _oldLevel;
    private Server _server;
    private LocalConnector _local;
    private final List<Long> _latencies = new CopyOnWriteArrayList<>();

    @BeforeEach
    public void setUp() throws Exception
    {
        _server = new Server();
        _local = new LocalConnector(_server, new HttpConnectionFactory());
        _server.addConnector(_local);

        Handler handler = new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                String path = request.getHttpURI().getPath();
                if (path.endsWith("/fail"))
                    callback.failed(new Exception());
                else
                    callback.succeeded();
                return true;
            }
        };
        AbstractLatencyRecordingHandler latencyRecordingHandler = new AbstractLatencyRecordingHandler()
        {
            @Override
            protected void onRequestComplete(long durationInNs)
            {
                _latencies.add(durationInNs);
            }
        };
        latencyRecordingHandler.setHandler(handler);

        ContextHandler contextHandler = new ContextHandler("/ctx");
        contextHandler.setHandler(latencyRecordingHandler);

        _server.setHandler(contextHandler);
        _server.start();

        // Disable WARN logs of failed requests.
        JettyLogger logger = (JettyLogger)LoggerFactory.getLogger(Response.class);
        _oldLevel = logger.getLevel();
        logger.setLevel(JettyLevel.OFF);
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        _server.stop();
        _latencies.clear();

        JettyLogger logger = (JettyLogger)LoggerFactory.getLogger(Response.class);
        logger.setLevel(_oldLevel);
    }

    @Test
    public void testLatenciesRecodingUponSuccess() throws Exception
    {
        for (int i = 0; i < 100; i++)
        {
            String response = _local.getResponse("GET /ctx/succeed HTTP/1.1\r\nHost: localhost\r\n\r\n");
            assertThat(response, containsString(" 200 OK"));
        }
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(_latencies::size, is(100));
        for (Long latency : _latencies)
        {
            assertThat(latency, greaterThan(0L));
        }
    }

    @Test
    public void testLatenciesRecodingUponFailure() throws Exception
    {
        for (int i = 0; i < 100; i++)
        {
            String response = _local.getResponse("GET /ctx/fail HTTP/1.1\r\nHost: localhost\r\n\r\n");
            assertThat(response, containsString(" 500 Server Error"));
        }
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(_latencies::size, is(100));
        for (Long latency : _latencies)
        {
            assertThat(latency, greaterThan(0L));
        }
    }
}
