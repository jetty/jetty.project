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

package org.eclipse.jetty.websocket.tests.proxy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebSocketProxy
{
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketProxy.class);

    private final WebSocketClient client;
    private final URI serverUri;
    private final ClientToProxy clientToProxy = new ClientToProxy();
    private final ProxyToServer proxyToServer = new ProxyToServer();

    public WebSocketProxy(WebSocketClient webSocketClient, URI serverUri)
    {
        this.client = webSocketClient;
        this.serverUri = serverUri;
    }

    public Session.Listener getSessionListener()
    {
        return clientToProxy;
    }

    public boolean awaitClose(long timeout)
    {
        try
        {
            if (!clientToProxy.closeLatch.await(timeout, TimeUnit.MILLISECONDS))
                return false;
            if (proxyToServer.getSession() == null)
                return true;
            return proxyToServer.closeLatch.await(timeout, TimeUnit.MILLISECONDS);
        }
        catch (Exception e)
        {
            return false;
        }
    }

    public class ClientToProxy implements Session.Listener
    {
        private volatile Session session;
        private final CountDownLatch closeLatch = new CountDownLatch(1);

        public Session getSession()
        {
            return session;
        }

        public void fail(Throwable failure)
        {
            String reason = failure.getMessage();
            session.close(StatusCode.SERVER_ERROR, reason, Callback.NOOP);
        }

        @Override
        public void onWebSocketOpen(Session session)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} onWebSocketOpen({})", getClass().getSimpleName(), session);

            try
            {
                this.session = session;
                ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
                upgradeRequest.setSubProtocols(session.getUpgradeRequest().getSubProtocols());
                upgradeRequest.setExtensions(session.getUpgradeRequest().getExtensions());
                client.connect(proxyToServer, serverUri, upgradeRequest)
                    // Only demand for frames after the connect() is successful.
                    .thenAccept(ignored -> session.demand());
            }
            catch (IOException x)
            {
                throw new UncheckedIOException(x);
            }
        }

        @Override
        public void onWebSocketPartialBinary(ByteBuffer payload, boolean fin, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} onWebSocketPartialBinary({}, {})", getClass().getSimpleName(), BufferUtil.toDetailString(payload), fin);

            Callback.Completable.with(c -> proxyToServer.getSession().sendPartialBinary(payload, fin, c))
                .thenRun(callback::succeed)
                .thenRun(session::demand)
                .exceptionally(x ->
                {
                    callback.fail(x);
                    fail(x);
                    return null;
                });
        }

        @Override
        public void onWebSocketPartialText(String payload, boolean fin)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} onWebSocketPartialText({}, {})", getClass().getSimpleName(), StringUtil.truncate(payload, 100), fin);

            proxyToServer.getSession().sendPartialText(payload, fin, Callback.from(session::demand, this::fail));
        }

        @Override
        public void onWebSocketPing(ByteBuffer payload)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} onWebSocketPing({})", getClass().getSimpleName(), BufferUtil.toDetailString(payload));

            proxyToServer.getSession().sendPing(payload, Callback.from(session::demand, this::fail));
        }

        @Override
        public void onWebSocketPong(ByteBuffer payload)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} onWebSocketPong({})", getClass().getSimpleName(), BufferUtil.toDetailString(payload));

            proxyToServer.getSession().sendPong(payload, Callback.from(session::demand, this::fail));
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} onWebSocketError()", getClass().getSimpleName(), cause);

            proxyToServer.fail(cause);
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} onWebSocketClose({} {})", getClass().getSimpleName(), statusCode, reason);

            // Session may be null if connection to the server failed.
            Session proxyToServerSession = proxyToServer.getSession();
            if (proxyToServerSession != null)
                proxyToServerSession.close(statusCode, reason, Callback.NOOP);
            closeLatch.countDown();
        }
    }

    public class ProxyToServer implements Session.Listener
    {
        private volatile Session session;
        private final CountDownLatch closeLatch = new CountDownLatch(1);

        public Session getSession()
        {
            return session;
        }

        public void fail(Throwable failure)
        {
            // Only ProxyToServer can be failed before it is opened (if ClientToProxy fails before the connect completes).
            Session session = this.session;
            if (session != null)
                session.close(StatusCode.SERVER_ERROR, failure.getMessage(), Callback.NOOP);
        }

        @Override
        public void onWebSocketOpen(Session session)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} onWebSocketOpen({})", getClass().getSimpleName(), session);

            this.session = session;
            session.demand();
        }

        @Override
        public void onWebSocketPartialBinary(ByteBuffer payload, boolean fin, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} onWebSocketPartialBinary({}, {})", getClass().getSimpleName(), BufferUtil.toDetailString(payload), fin);

            Callback.Completable.with(c -> clientToProxy.getSession().sendPartialBinary(payload, fin, c))
                .thenRun(callback::succeed)
                .thenRun(session::demand)
                .exceptionally(x ->
                {
                    callback.fail(x);
                    fail(x);
                    return null;
                });
        }

        @Override
        public void onWebSocketPartialText(String payload, boolean fin)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} onWebSocketPartialText({}, {})", getClass().getSimpleName(), StringUtil.truncate(payload, 100), fin);

            clientToProxy.getSession().sendPartialText(payload, fin, Callback.from(session::demand, this::fail));
        }

        @Override
        public void onWebSocketPing(ByteBuffer payload)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} onWebSocketPing({})", getClass().getSimpleName(), BufferUtil.toDetailString(payload));

            clientToProxy.getSession().sendPing(payload, Callback.from(session::demand, this::fail));
        }

        @Override
        public void onWebSocketPong(ByteBuffer payload)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} onWebSocketPong({})", getClass().getSimpleName(), BufferUtil.toDetailString(payload));

            clientToProxy.getSession().sendPong(payload, Callback.from(session::demand, this::fail));
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} onWebSocketError()", getClass().getSimpleName(), cause);

            clientToProxy.fail(cause);
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} onWebSocketClose({} {})", getClass().getSimpleName(), statusCode, reason);

            Session clientToProxySession = clientToProxy.getSession();
            clientToProxySession.close(statusCode, reason, Callback.NOOP);
            closeLatch.countDown();
        }
    }
}
