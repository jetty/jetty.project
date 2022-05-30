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

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.internal.HttpConnection;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.BeforeEach;

/**
 * Extended Server Tester.
 */
public class DelayedServerTest extends HttpServerTestBase
{
    @BeforeEach
    public void init() throws Exception
    {
        initServer(new ServerConnector(_server, new HttpConnectionFactory()
        {
            @Override
            public Connection newConnection(Connector connector, EndPoint endPoint)
            {
                DelayedHttpConnection connection = new DelayedHttpConnection(getHttpConfiguration(), connector, endPoint);
                connection.setUseInputDirectByteBuffers(isUseInputDirectByteBuffers());
                connection.setUseOutputDirectByteBuffers(isUseOutputDirectByteBuffers());
                return configure(connection, connector, endPoint);
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
        protected HttpStreamOverHTTP1 newHttpStream(String method, String uri, HttpVersion version)
        {
            return new HttpStreamOverHTTP1(method, uri, version)
            {
                @Override
                public void send(MetaData.Request request, MetaData.Response response, boolean last, ByteBuffer content, Callback callback)
                {
                    DelayedCallback delay = new DelayedCallback(callback);
                    super.send(request, response, last, content, delay);
                }
            };
        }
    }

    private static class DelayedCallback extends Callback.Nested
    {
        public DelayedCallback(Callback callback)
        {
            super(callback);
        }

        @Override
        public void succeeded()
        {
            new Thread(() ->
            {
                try
                {
                    Thread.sleep(2);
                    Thread.yield();
                }
                catch (InterruptedException ignored)
                {
                    // ignored
                }
                finally
                {
                    super.succeeded();
                }
            }).start();
        }

        @Override
        public void failed(Throwable x)
        {
            new Thread(() ->
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
            }).start();
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }
    }
}
