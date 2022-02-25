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

package org.eclipse.jetty.server;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.junit.jupiter.api.BeforeEach;

/**
 * Extended Server Tester.
 */
public class DelayedServerTest extends HttpServerTestBase
{
    @BeforeEach
    public void init() throws Exception
    {
        startServer(new ServerConnector(_server, new HttpConnectionFactory()
        {
            @Override
            public Connection newConnection(Connector connector, EndPoint endPoint)
            {
                return configure(new DelayedHttpConnection(getHttpConfiguration(), connector, endPoint), connector, endPoint);
            }
        }));
    }

    private static class DelayedHttpConnection extends HttpConnection
    {
        public DelayedHttpConnection(HttpConfiguration config, Connector connector, EndPoint endPoint)
        {
            super(config, connector, endPoint, false);
        }

        @Override
        public void send(MetaData.Request request, MetaData.Response response, ByteBuffer content, boolean lastContent, Callback callback)
        {
            DelayedCallback delay = new DelayedCallback(callback, getServer().getThreadPool());
            super.send(request, response, content, lastContent, delay);
        }
    }

    private static class DelayedCallback extends Callback.Nested
    {
        final ThreadPool pool;

        public DelayedCallback(Callback callback, ThreadPool threadPool)
        {
            super(callback);
            pool = threadPool;
        }

        @Override
        public void succeeded()
        {
            pool.execute(() ->
            {
                try
                {
                    Thread.sleep(10);
                }
                catch (InterruptedException ignored)
                {
                    // ignored
                }
                finally
                {
                    super.succeeded();
                }
            });
        }

        @Override
        public void failed(Throwable x)
        {
            pool.execute(() ->
            {
                try
                {
                    Thread.sleep(20);
                }
                catch (InterruptedException ignored)
                {
                    // ignored
                }
                finally
                {
                    super.failed(x);
                }
            });
        }
    }
}
