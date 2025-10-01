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

package org.eclipse.jetty.test.webapp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http2.RateControl;
import org.eclipse.jetty.http2.WindowRateControl;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;

@Tag("stress")
@Timeout(value = 5, unit = TimeUnit.MINUTES)
public class StressHTTP2AbortTest
{
    private static final Logger LOG = LoggerFactory.getLogger(StressHTTP2AbortTest.class);

    private static final byte[] DATA = """
        [start]
        Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.
        Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure
        dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat
        non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.
        [end]
        """.repeat(50).getBytes(StandardCharsets.UTF_8);

    private Server server;

    @AfterEach
    public void tearDown()
    {
        if (server != null)
            LifeCycle.stop(server);
    }

    private void start(Handler handler) throws Exception
    {
        server = new Server();

        HttpConfiguration httpConfiguration = new HttpConfiguration();
        HTTP2CServerConnectionFactory connectionFactory = new HTTP2CServerConnectionFactory(httpConfiguration);
        connectionFactory.setRateControlFactory(new RateControl.Factory()
        {
            @Override
            public RateControl newRateControl(EndPoint endPoint)
            {
                return new WindowRateControl(8, Duration.ofSeconds(1));
            }
        });
        ServerConnector serverConnector = new ServerConnector(server, connectionFactory);
        serverConnector.setPort(0);
        server.addConnector(serverConnector);

        server.setHandler(handler);

        server.start();
    }

    @Test
    public void testOutputWithAborts() throws Exception
    {
        int iterations = 30;
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, Response response, Callback callback) throws Exception
            {
                response.setStatus(200);
                try (Blocker.Callback blocker = Blocker.callback())
                {
                    response.write(true, ByteBuffer.wrap(DATA), blocker);
                    blocker.block();
                    callback.succeeded();
                }
                catch (Throwable x)
                {
                    callback.failed(x);
                    throw x;
                }
                return true;
            }
        });

        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP2(new HTTP2Client())))
        {
            httpClient.start();

            for (int i = 0; i < iterations; i++)
            {
                CompletableFuture<Object> cf = new CompletableFuture<>();
                Request request = httpClient.newRequest(server.getURI());
                request.path("/" + i)
                    .method(HttpMethod.GET)
                    .send(result ->
                    {
                        if (result.isSucceeded())
                            cf.complete(null);
                        else
                            cf.completeExceptionally(result.getFailure());
                    });

                if (i % 2 == 0)
                    request.abort(new Exception("client abort #" + i));

                try
                {
                    cf.get(10, TimeUnit.SECONDS);
                }
                catch (Exception e)
                {
                    Throwable cause = e.getCause();
                    if (cause instanceof TimeoutException)
                    {
                        LOG.error("error in req #" + i, e);
                        errors.add(e);
                    }
                }
            }

//            LOG.info("*** server dump ***");
//            LOG.info(server.dump());
//            LOG.info("*** client dump ***");
//            LOG.info(httpClient.dump());
        }
        assertThat(errors, empty());
    }
}
