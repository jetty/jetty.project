/*******************************************************************************
 * Copyright (c) 2011 Intalio, Inc.
 * ======================================================================
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *   The Eclipse Public License is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 *
 *   The Apache License v2.0 is available at
 *   http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 *******************************************************************************/
package org.eclipse.jetty.websocket.client;

import java.io.EOFException;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.AsyncConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.SelectChannelEndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.extensions.Extension;
import org.eclipse.jetty.websocket.masks.Masker;

/* ------------------------------------------------------------ */
/**
 * <p>WebSocketClientFactory contains the common components needed by multiple {@link OldWebSocketClient} instances
 * (for example, a {@link ThreadPool}, a {@link SelectorManager NIO selector}, etc).</p>
 * <p>WebSocketClients with different configurations should share the same factory to avoid to waste resources.</p>
 * <p>If a ThreadPool or MaskGen is passed in the constructor, then it is not added with {@link AggregateLifeCycle#addBean(Object)},
 * so it's lifecycle must be controlled externally.
 *
 * @see OldWebSocketClient
 */
public class OldWebSocketClientFactory extends AggregateLifeCycle
{
    private final static Logger LOG = Log.getLogger(OldWebSocketClientFactory.class);
    private final Queue<WebSocketConnection> connections = new ConcurrentLinkedQueue<WebSocketConnection>();
    private WebSocketPolicy policy;

    /**
     * <p>Creates a WebSocketClientFactory with the default configuration.</p>
     */
    public OldWebSocketClientFactory()
    {
        policy = WebSocketPolicy.newClientPolicy();
    }
    
    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    @Override
    protected void doStop() throws Exception
    {
        closeConnections();
        super.doStop();
    }

    /**
     * <p>Creates and returns a new instance of a {@link OldWebSocketClient}, configured with this
     * WebSocketClientFactory instance.</p>
     *
     * @return a new {@link OldWebSocketClient} instance
     */
    public OldWebSocketClient newWebSocketClient()
    {
        return new OldWebSocketClient(this);
    }

    protected boolean addConnection(WebSocketConnection connection)
    {
        return isRunning() && connections.add(connection);
    }

    protected boolean removeConnection(WebSocketConnection connection)
    {
        return connections.remove(connection);
    }

    protected void closeConnections()
    {
        for (WebSocketConnection connection : connections)
        {
            try
            {
                connection.close();
            }
            catch (IOException e)
            {
                LOG.info(e);
            }
        }
    }

    /**
     * WebSocket Client Selector Manager
     */
    class WebSocketClientSelector extends SelectorManager
    {
        @Override
        public boolean dispatch(Runnable task)
        {
            return _threadPool.dispatch(task);
        }

        @Override
        protected SelectChannelEndPoint newEndPoint(SocketChannel channel, SelectSet selectSet, final SelectionKey key) throws IOException
        {
            OldWebSocketClient.WebSocketFuture holder = (OldWebSocketClient.WebSocketFuture)key.attachment();
            int maxIdleTime = holder.getMaxIdleTime();
            if (maxIdleTime < 0)
                maxIdleTime = (int)getMaxIdleTime();
            SelectChannelEndPoint result = new SelectChannelEndPoint(channel, selectSet, key, maxIdleTime);
            AsyncEndPoint endPoint = result;

            // Detect if it is SSL, and wrap the connection if so
            if ("wss".equals(holder.getURI().getScheme()))
            {
                SSLEngine sslEngine = newSslEngine(channel);
                SslConnection sslConnection = new SslConnection(sslEngine, endPoint);
                endPoint.setConnection(sslConnection);
                endPoint = sslConnection.getSslEndPoint();
            }

            AsyncConnection connection = selectSet.getManager().newConnection(channel, endPoint, holder);
            endPoint.setConnection(connection);

            return result;
        }

        @Override
        public AsyncConnection newConnection(SocketChannel channel, AsyncEndPoint endpoint, Object attachment)
        {
            OldWebSocketClient.WebSocketFuture holder = (OldWebSocketClient.WebSocketFuture)attachment;
            return new HandshakeConnection(endpoint, holder);
        }

        @Override
        protected void endPointOpened(SelectChannelEndPoint endpoint)
        {
            // TODO expose on outer class ??
        }

        @Override
        protected void endPointUpgraded(ConnectedEndPoint endpoint, Connection oldConnection)
        {
            LOG.debug("upgrade {} -> {}", oldConnection, endpoint.getConnection());
        }

        @Override
        protected void endPointClosed(SelectChannelEndPoint endpoint)
        {
            endpoint.getConnection().onClose();
        }

        @Override
        protected void connectionFailed(SocketChannel channel, Throwable ex, Object attachment)
        {
            if (!(attachment instanceof OldWebSocketClient.WebSocketFuture))
                super.connectionFailed(channel, ex, attachment);
            else
            {
                __log.debug(ex);
                OldWebSocketClient.WebSocketFuture future = (OldWebSocketClient.WebSocketFuture)attachment;

                future.handshakeFailed(ex);
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Handshake Connection.
     * Handles the connection until the handshake succeeds or fails.
     */
    class HandshakeConnection extends AbstractConnection implements AsyncConnection
    {
        private final AsyncEndPoint _endp;
        private final OldWebSocketClient.WebSocketFuture _future;
        private final String _key;
        private final HttpParser _parser;
        private String _accept;
        private String _error;
        private ByteArrayBuffer _handshake;

        public HandshakeConnection(AsyncEndPoint endpoint, OldWebSocketClient.WebSocketFuture future)
        {
            super(endpoint, System.currentTimeMillis());
            _endp = endpoint;
            _future = future;

            byte[] bytes = new byte[16];
            new Random().nextBytes(bytes);
            _key = new String(B64Code.encode(bytes));

            Buffers buffers = new SimpleBuffers(_buffers.getBuffer(), null);
            _parser = new HttpParser(buffers, _endp, new HttpParser.EventHandler()
            {
                @Override
                public void startResponse(ByteBuffer version, int status, ByteBuffer reason) throws IOException
                {
                    if (status != 101)
                    {
                        _error = "Bad response status " + status + " " + reason;
                        _endp.close();
                    }
                }

                @Override
                public void parsedHeader(ByteBuffer name, ByteBuffer value) throws IOException
                {
                    if (__ACCEPT.equals(name))
                        _accept = value.toString();
                }

                @Override
                public void startRequest(ByteBuffer method, ByteBuffer url, ByteBuffer version) throws IOException
                {
                    if (_error == null)
                        _error = "Bad response: " + method + " " + url + " " + version;
                    _endp.close();
                }

                @Override
                public void content(ByteBuffer ref) throws IOException
                {
                    if (_error == null)
                        _error = "Bad response. " + ref.length() + "B of content?";
                    _endp.close();
                }
            });
        }

        private boolean handshake()
        {
            if (_handshake==null)
            {
                String path = _future.getURI().getPath();
                if (path == null || path.length() == 0)
                    path = "/";

                if (_future.getURI().getRawQuery() != null)
                    path += "?" + _future.getURI().getRawQuery();

                String origin = _future.getOrigin();

                StringBuilder request = new StringBuilder(512);
                request.append("GET ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(_future.getURI().getHost()).append(":")
                .append(_future.getURI().getPort()).append("\r\n")
                .append("Upgrade: websocket\r\n")
                .append("Connection: Upgrade\r\n")
                .append("Sec-WebSocket-Key: ")
                .append(_key).append("\r\n");

                if (origin != null)
                    request.append("Origin: ").append(origin).append("\r\n");

                request.append("Sec-WebSocket-Version: ").append(WebSocketConnectionRFC6455.VERSION).append("\r\n");

                if (_future.getProtocol() != null)
                    request.append("Sec-WebSocket-Protocol: ").append(_future.getProtocol()).append("\r\n");

                Map<String, String> cookies = _future.getCookies();
                if (cookies != null && cookies.size() > 0)
                {
                    for (String cookie : cookies.keySet())
                        request.append("Cookie: ")
                        .append(QuotedStringTokenizer.quoteIfNeeded(cookie, HttpFields.__COOKIE_DELIM))
                        .append("=")
                        .append(QuotedStringTokenizer.quoteIfNeeded(cookies.get(cookie), HttpFields.__COOKIE_DELIM))
                        .append("\r\n");
                }

                request.append("\r\n");

                _handshake=new ByteArrayBuffer(request.toString(), false);
            }
            
            // TODO extensions

            try
            {
                int len = _handshake.length();
                int flushed = _endp.flush(_handshake);
                if (flushed<0)
                    throw new IOException("incomplete handshake");
            }
            catch (IOException e)
            {
                _future.handshakeFailed(e);
            }
            return _handshake.length()==0;
        }

        public Connection handle() throws IOException
        {
            while (_endp.isOpen() && !_parser.isComplete())
            {
                if (_handshake==null || _handshake.length()>0)
                    if (!handshake())
                        return this;

                if (!_parser.parseAvailable())
                {
                    if (_endp.isInputShutdown())
                        _future.handshakeFailed(new IOException("Incomplete handshake response"));
                    return this;
                }
            }
            if (_error == null)
            {
                if (_accept == null)
                {
                    _error = "No Sec-WebSocket-Accept";
                }
                else if (!WebSocketConnectionRFC6455.hashKey(_key).equals(_accept))
                {
                    _error = "Bad Sec-WebSocket-Accept";
                }
                else
                {
                    WebSocketConnection connection = newWebSocketConnection();

                    ByteBuffer header = _parser.getHeaderBuffer();
                    if (header.hasContent())
                        connection.fillBuffersFrom(header);
                    _buffers.returnBuffer(header);

                    _future.onConnection(connection);

                    return connection;
                }
            }

            _endp.close();
            return this;
        }

        private WebSocketConnection newWebSocketConnection() throws IOException
        {
            return new WebSocketClientConnection(
                    _future._client.getFactory(),
                    _future.getWebSocket(),
                    _endp,
                    _buffers,
                    System.currentTimeMillis(),
                    _future.getMaxIdleTime(),
                    _future.getProtocol(),
                    null,
                    WebSocketConnectionRFC6455.VERSION,
                    _future.getMaskGen());
        }

        public void onInputShutdown() throws IOException
        {
            _endp.close();
        }

        public boolean isIdle()
        {
            return false;
        }

        public boolean isSuspended()
        {
            return false;
        }

        public void onClose()
        {
            if (_error != null)
                _future.handshakeFailed(new ProtocolException(_error));
            else
                _future.handshakeFailed(new EOFException());
        }
    }

    private static class WebSocketClientConnection extends WebSocketConnectionRFC6455
    {
        private final OldWebSocketClientFactory factory;

        public WebSocketClientConnection(OldWebSocketClientFactory factory, WebSocket webSocket, EndPoint endPoint, WebSocketBuffers buffers, long timeStamp, int maxIdleTime, String protocol, List<Extension> extensions, int draftVersion, Masker maskGen) throws IOException
        {
            super(webSocket, endPoint, buffers, timeStamp, maxIdleTime, protocol, extensions, draftVersion, maskGen);
            this.factory = factory;
        }

        @Override
        public void onClose()
        {
            super.onClose();
            factory.removeConnection(this);
        }
    }
}
