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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.HTTP2Stream;
import org.eclipse.jetty.http2.HTTP2StreamEndPoint;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.TunnelSupport;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.NanoTime;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TunnelStreamLeakTest extends AbstractTest
{
    private static final long STOP_TIMEOUT = 5000;

    private final CompletableFuture<Connection> tunnel = new CompletableFuture<>();

    /**
     * Starts a server that upgrades an extended CONNECT request to a tunnel, in the same
     * way {@code RFC8441Handshaker} does for WebSocket over HTTP/2, but with a trivial
     * tunnel {@link Connection} so that the test can drive the tunnel deterministically.
     */
    private void startTunnelServer() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                TunnelSupport tunnelSupport = request.getTunnelSupport();
                if (tunnelSupport == null || tunnelSupport.getProtocol() == null)
                {
                    Response.writeError(request, response, callback, HttpStatus.METHOD_NOT_ALLOWED_405);
                    return true;
                }

                Executor executor = request.getConnectionMetaData().getConnector().getExecutor();
                Connection connection = new TunnelConnection(tunnelSupport.getEndPoint(), executor);
                // A 2xx response plus this attribute is what makes HttpStreamOverHTTP2
                // upgrade the stream to a tunnel and attach the endPoint to the stream.
                request.setAttribute(HttpStream.UPGRADE_CONNECTION_ATTRIBUTE, connection);
                response.setStatus(HttpStatus.OK_200);
                response.write(true, null, callback);
                return true;
            }
        });

        // The graceful block in Server.doStop() is skipped altogether when stopTimeout is 0.
        server.setStopTimeout(STOP_TIMEOUT);
        // The connector's shutdown idleTimeout would close the connection while the
        // graceful shutdown is in progress; keep it well beyond the stop timeout so
        // that the test only measures the pending stream count.
        connector.setShutdownIdleTimeout(4 * STOP_TIMEOUT);
    }

    /**
     * Performs an extended CONNECT and returns the client-side stream once the tunnel is
     * established on both peers.
     */
    private Stream connectTunnel(Session clientSession) throws Exception
    {
        String authority = "localhost:" + connector.getLocalPort();
        MetaData.Request request = new MetaData.ConnectRequest(HttpScheme.HTTP, new HostPortHttpField(authority), "/tunnel", HttpFields.EMPTY, "websocket");
        CountDownLatch responseLatch = new CountDownLatch(1);
        FuturePromise<Stream> promise = new FuturePromise<>();
        clientSession.newStream(new HeadersFrame(request, null, false), promise, new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                if (response.getStatus() == HttpStatus.OK_200)
                    responseLatch.countDown();
            }
        });
        Stream clientStream = promise.get(5, TimeUnit.SECONDS);
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS), "tunnel was not established");
        // Wait for the server-side upgrade, so that closing the tunnel goes through
        // HTTP2StreamEndPoint.close() rather than racing with the upgrade itself.
        tunnel.get(5, TimeUnit.SECONDS);
        return clientStream;
    }

    private HTTP2Stream serverStream()
    {
        HTTP2StreamEndPoint endPoint = (HTTP2StreamEndPoint)tunnel.join().getEndPoint();
        return (HTTP2Stream)endPoint.getTransport();
    }

    @Test
    public void testResetOfClosedTunnelStreamAllowsGracefulShutdown() throws Exception
    {
        startTunnelServer();

        CountDownLatch pingLatch = new CountDownLatch(1);
        Session clientSession = newClientSession(new Session.Listener()
        {
            @Override
            public void onPing(Session session, PingFrame frame)
            {
                if (frame.isReply())
                    pingLatch.countDown();
            }
        });
        Stream clientStream = connectTunnel(clientSession);
        HTTP2Stream serverStream = serverStream();

        // The application closes the tunnel, e.g. when the WebSocket session is closed.
        tunnel.join().getEndPoint().close();
        await().atMost(5, TimeUnit.SECONDS).until(serverStream::isClosed);

        // The client resets the stream, as it does when it closes the tunnel from its side.
        Callback.Completable resetCallback = new Callback.Completable();
        clientStream.reset(new ResetFrame(clientStream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), resetCallback);
        resetCallback.get(5, TimeUnit.SECONDS);

        // Frames of a session are processed in order, so a PING reply proves that
        // the server has already processed the RST_STREAM.
        clientSession.ping(new PingFrame(false), Callback.NOOP);
        assertTrue(pingLatch.await(5, TimeUnit.SECONDS), "PING was not replied to");

        // Server.doStop() waits for Graceful.shutdown(server) for at most stopTimeout.
        long start = NanoTime.now();
        server.stop();
        assertThat(NanoTime.millisSince(start), lessThan(STOP_TIMEOUT / 2));
    }

    @Test
    public void testClosedTunnelStreamIsRemovedFromSession() throws Exception
    {
        startTunnelServer();

        Session clientSession = newClientSession(new Session.Listener() {});
        connectTunnel(clientSession);
        HTTP2Session serverSession = serverStream().getSession();

        tunnel.join().getEndPoint().close();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(serverSession.getStreams(), is(empty())));
    }

    private class TunnelConnection extends AbstractConnection
    {
        private TunnelConnection(EndPoint endPoint, Executor executor)
        {
            super(endPoint, executor);
        }

        @Override
        public void onOpen()
        {
            super.onOpen();
            // Called by HTTP2StreamEndPoint.upgrade(Connection): the tunnel is now live.
            tunnel.complete(this);
        }

        @Override
        public void onFillable()
        {
            // The test does not tunnel any bytes.
        }
    }
}
