//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.tests.proxy;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketConnectionListener;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPartialListener;
import org.eclipse.jetty.websocket.api.WebSocketPingPongListener;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;

public class WebSocketProxy
{
    private static final Logger LOG = Log.getLogger(WebSocketProxy.class);

    private final WebSocketClient client;
    private final URI serverUri;
    private final ClientToProxy clientToProxy = new ClientToProxy();
    private final ProxyToServer proxyToServer = new ProxyToServer();

    public WebSocketProxy(WebSocketClient webSocketClient, URI serverUri)
    {
        this.client = webSocketClient;
        this.serverUri = serverUri;
    }

    public WebSocketConnectionListener getWebSocketConnectionListener()
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

    public class ClientToProxy implements WebSocketPartialListener, WebSocketPingPongListener
    {
        private volatile Session session;
        private final CountDownLatch closeLatch = new CountDownLatch(1);
        private final AtomicInteger pingsReceived = new AtomicInteger();

        public Session getSession()
        {
            return session;
        }

        public void fail(Throwable failure)
        {
            session.close(StatusCode.SERVER_ERROR, failure.getMessage());
        }

        @Override
        public void onWebSocketConnect(Session session)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} onWebSocketConnect({})", getClass().getSimpleName(), session);

            Future<Session> connect = null;
            try
            {
                this.session = session;
                ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
                upgradeRequest.setSubProtocols(session.getUpgradeRequest().getSubProtocols());
                upgradeRequest.setExtensions(session.getUpgradeRequest().getExtensions());
                connect = client.connect(proxyToServer, serverUri, upgradeRequest);

                //This is blocking as we really want the client to be connected before receiving any messages.
                connect.get();
            }
            catch (Exception e)
            {
                if (connect != null)
                    connect.cancel(true);
                throw new WebSocketException(e);
            }
        }

        @Override
        public void onWebSocketPartialBinary(ByteBuffer payload, boolean fin)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} onWebSocketPartialBinary({}, {})", getClass().getSimpleName(), BufferUtil.toDetailString(payload), fin);

            try
            {
                proxyToServer.getSession().getRemote().sendPartialBytes(payload, fin);
            }
            catch (Exception e)
            {
                throw new WebSocketException(e);
            }
        }

        @Override
        public void onWebSocketPartialText(String payload, boolean fin)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} onWebSocketPartialText({}, {})", getClass().getSimpleName(), StringUtil.truncate(payload, 100), fin);

            try
            {
                proxyToServer.getSession().getRemote().sendPartialString(payload, fin);
            }
            catch (Exception e)
            {
                throw new WebSocketException(e);
            }
        }

        @Override
        public void onWebSocketPing(ByteBuffer payload)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} onWebSocketPing({})", getClass().getSimpleName(), BufferUtil.toDetailString(payload));

            try
            {
                // The implementation automatically sends pong response.
                pingsReceived.incrementAndGet();
                proxyToServer.getSession().getRemote().sendPing(BufferUtil.copy(payload));
            }
            catch (Exception e)
            {
                throw new WebSocketException(e);
            }
        }

        @Override
        public void onWebSocketPong(ByteBuffer payload)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} onWebSocketPong({})", getClass().getSimpleName(), BufferUtil.toDetailString(payload));

            try
            {
                // If we have sent out a ping then we have already responded with automatic pong.
                // If this is an unsolicited pong we still need to forward it to the server.
                int valueBeforeUpdate = proxyToServer.pingsReceived.getAndUpdate(i -> i > 0 ? i - 1 : i);
                if (valueBeforeUpdate == 0)
                    proxyToServer.getSession().getRemote().sendPong(BufferUtil.copy(payload));
            }
            catch (Exception e)
            {
                throw new WebSocketException(e);
            }
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
            Session session = proxyToServer.getSession();
            if (session != null)
                session.close(statusCode, reason);
            closeLatch.countDown();
        }
    }

    public class ProxyToServer implements WebSocketPartialListener, WebSocketPingPongListener
    {
        private volatile Session session;
        private final CountDownLatch closeLatch = new CountDownLatch(1);
        private final AtomicInteger pingsReceived = new AtomicInteger();

        public Session getSession()
        {
            return session;
        }

        public void fail(Throwable failure)
        {
            // Only ProxyToServer can be failed before it is opened (if ClientToProxy fails before the connect completes).
            Session session = this.session;
            if (session != null)
                session.close(StatusCode.SERVER_ERROR, failure.getMessage());
        }

        @Override
        public void onWebSocketConnect(Session session)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} onWebSocketConnect({})", getClass().getSimpleName(), session);

            this.session = session;
        }

        @Override
        public void onWebSocketPartialBinary(ByteBuffer payload, boolean fin)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} onWebSocketPartialBinary({}, {})", getClass().getSimpleName(), BufferUtil.toDetailString(payload), fin);

            try
            {
                clientToProxy.getSession().getRemote().sendPartialBytes(payload, fin);
            }
            catch (Exception e)
            {
                throw new WebSocketException(e);
            }
        }

        @Override
        public void onWebSocketPartialText(String payload, boolean fin)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} onWebSocketPartialText({}, {})", getClass().getSimpleName(), StringUtil.truncate(payload, 100), fin);

            try
            {
                clientToProxy.getSession().getRemote().sendPartialString(payload, fin);
            }
            catch (Exception e)
            {
                throw new WebSocketException(e);
            }
        }

        @Override
        public void onWebSocketPing(ByteBuffer payload)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} onWebSocketPing({})", getClass().getSimpleName(), BufferUtil.toDetailString(payload));

            try
            {
                // The implementation automatically sends pong response.
                pingsReceived.incrementAndGet();
                clientToProxy.getSession().getRemote().sendPing(BufferUtil.copy(payload));
            }
            catch (Exception e)
            {
                throw new WebSocketException(e);
            }
        }

        @Override
        public void onWebSocketPong(ByteBuffer payload)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} onWebSocketPong({})", getClass().getSimpleName(), BufferUtil.toDetailString(payload));

            try
            {
                // If we have sent out a ping then we have already responded with automatic pong.
                // If this is an unsolicited pong we still need to forward it to the client.
                int valueBeforeUpdate = clientToProxy.pingsReceived.getAndUpdate(i -> i > 0 ? i - 1 : i);
                if (valueBeforeUpdate == 0)
                    clientToProxy.getSession().getRemote().sendPong(BufferUtil.copy(payload));
            }
            catch (Exception e)
            {
                throw new WebSocketException(e);
            }
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

            clientToProxy.getSession().close(statusCode, reason);
            closeLatch.countDown();
        }
    }
}
