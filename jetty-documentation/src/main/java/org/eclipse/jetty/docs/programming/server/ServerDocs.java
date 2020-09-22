//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.docs.programming.server;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.DetectorConnectionFactory;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.ajax.AsyncJSON;
import org.eclipse.jetty.util.ssl.SslContextFactory;

@SuppressWarnings("unused")
public class ServerDocs
{
    public void http() throws Exception
    {
        // tag::http[]
        // Create the HTTP/1.1 ConnectionFactory.
        HttpConnectionFactory http = new HttpConnectionFactory();

        Server server = new Server();

        // Create the connector with the ConnectionFactory.
        ServerConnector connector = new ServerConnector(server, http);
        connector.setPort(8080);

        server.addConnector(connector);
        server.start();
        // end::http[]
    }

    public void tlsHttp() throws Exception
    {
        // tag::tlsHttp[]
        // Create the HTTP/1.1 ConnectionFactory.
        HttpConnectionFactory http = new HttpConnectionFactory();

        // Create and configure the TLS context factory.
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("/path/to/keystore.p12");
        sslContextFactory.setKeyStorePassword("secret");

        // Create the TLS ConnectionFactory,
        // setting HTTP/1.1 as the wrapped protocol.
        SslConnectionFactory tls = new SslConnectionFactory(sslContextFactory, http.getProtocol());

        Server server = new Server();

        // Create the connector with both ConnectionFactories.
        ServerConnector connector = new ServerConnector(server, tls, http);
        connector.setPort(8443);

        server.addConnector(connector);
        server.start();
        // end::tlsHttp[]
    }

    public void detector() throws Exception
    {
        // tag::detector[]
        // Create the HTTP/1.1 ConnectionFactory.
        HttpConnectionFactory http = new HttpConnectionFactory();

        // Create and configure the TLS context factory.
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("/path/to/keystore.p12");
        sslContextFactory.setKeyStorePassword("secret");

        // Create the TLS ConnectionFactory,
        // setting HTTP/1.1 as the wrapped protocol.
        SslConnectionFactory tls = new SslConnectionFactory(sslContextFactory, http.getProtocol());

        Server server = new Server();

        // Create the detector ConnectionFactory to
        // detect whether the initial bytes are TLS.
        DetectorConnectionFactory tlsDetector = new DetectorConnectionFactory(tls); // <1>

        // Create the connector with both ConnectionFactories.
        ServerConnector connector = new ServerConnector(server, tlsDetector, http); // <2>
        connector.setPort(8181);

        server.addConnector(connector);
        server.start();
        // end::detector[]
    }

    // tag::jsonHttpConnectionFactory[]
    public class JSONHTTPConnectionFactory extends AbstractConnectionFactory
    {
        public JSONHTTPConnectionFactory()
        {
            super("JSONHTTP");
        }

        @Override
        public Connection newConnection(Connector connector, EndPoint endPoint)
        {
            JSONHTTPConnection connection = new JSONHTTPConnection(endPoint, connector.getExecutor());
            // Call configure() to apply configurations common to all connections.
            return configure(connection, connector, endPoint);
        }
    }
    // end::jsonHttpConnectionFactory[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::jsonHttpConnection[]
    public class JSONHTTPConnection extends AbstractConnection
    {
        // The asynchronous JSON parser.
        private final AsyncJSON parser = new AsyncJSON.Factory().newAsyncJSON();
        private final IteratingCallback callback = new JSONHTTPIteratingCallback();

        public JSONHTTPConnection(EndPoint endPoint, Executor executor)
        {
            super(endPoint, executor);
        }

        @Override
        public void onOpen()
        {
            super.onOpen();

            // Declare interest in being called back when
            // there are bytes to read from the network.
            fillInterested();
        }

        @Override
        public void onFillable()
        {
            callback.iterate();
        }

        private class JSONHTTPIteratingCallback extends IteratingCallback
        {
            private ByteBuffer buffer;

            @Override
            protected Action process() throws Throwable
            {
                if (buffer == null)
                    buffer = BufferUtil.allocate(getInputBufferSize(), true);

                while (true)
                {
                    int filled = getEndPoint().fill(buffer);
                    if (filled > 0)
                    {
                        boolean parsed = parser.parse(buffer);
                        if (parsed)
                        {
                            Map<String, Object> request = parser.complete();

                            // Allow applications to process the request.
                            invokeApplication(request, this);

                            // Signal that the iteration should resume when
                            // the application completed the request processing.
                            return Action.SCHEDULED;
                        }
                        else
                        {
                            // Did not receive enough JSON bytes,
                            // loop around to try to read more.
                        }
                    }
                    else if (filled == 0)
                    {
                        // We don't need the buffer anymore, so
                        // don't keep it around while we are idle.
                        buffer = null;

                        // No more bytes to read, declare
                        // again interest for fill events.
                        fillInterested();

                        // Signal that the iteration is now IDLE.
                        return Action.IDLE;
                    }
                    else
                    {
                        // The other peer closed the connection,
                        // the iteration completed successfully.
                        return Action.SUCCEEDED;
                    }
                }
            }

            @Override
            protected void onCompleteSuccess()
            {
                getEndPoint().close();
            }

            @Override
            protected void onCompleteFailure(Throwable cause)
            {
                getEndPoint().close(cause);
            }
        }
    }
    // end::jsonHttpConnection[]

    private void invokeApplication(Map<String, Object> request, Callback callback)
    {
    }

    // tag::jsonHttpAPI[]
    class JSONHTTPRequest
    {
        // Request APIs
    }

    class JSONHTTPResponse
    {
        // Response APIs
    }

    interface JSONHTTPService
    {
        void service(JSONHTTPRequest request, JSONHTTPResponse response, Callback callback);
    }
    // end::jsonHttpAPI[]
}
