//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.channels.ByteChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/**
 * <p>{@link WebSocketClient} allows to create multiple connections to multiple destinations
 * that can speak the websocket protocol.</p>
 * <p>When creating websocket connections, {@link WebSocketClient} accepts a {@link WebSocket}
 * object (to receive events from the server), and returns a {@link WebSocket.Connection} to
 * send data to the server.</p>
 * <p>Example usage is as follows:</p>
 * <pre>
 *   WebSocketClientFactory factory = new WebSocketClientFactory();
 *   factory.start();
 *
 *   WebSocketClient client = factory.newWebSocketClient();
 *   // Configure the client
 *
 *   WebSocket.Connection connection = client.open(new URI("ws://127.0.0.1:8080/"), new WebSocket.OnTextMessage()
 *   {
 *     public void onOpen(Connection connection)
 *     {
 *       // open notification
 *     }
 *
 *     public void onClose(int closeCode, String message)
 *     {
 *       // close notification
 *     }
 *
 *     public void onMessage(String data)
 *     {
 *       // handle incoming message
 *     }
 *   }).get(5, TimeUnit.SECONDS);
 *
 *   connection.sendMessage("Hello World");
 * </pre>
 */
public class WebSocketClient
{
    private final static Logger __log = org.eclipse.jetty.util.log.Log.getLogger(WebSocketClient.class.getName());

    private final WebSocketClientFactory _factory;
    private final Map<String,String> _cookies=new ConcurrentHashMap<String, String>();
    private final List<String> _extensions=new CopyOnWriteArrayList<String>();
    private String _origin;
    private String _protocol;
    private int _maxIdleTime=-1;
    private int _maxTextMessageSize=16*1024;
    private int _maxBinaryMessageSize=-1;
    private MaskGen _maskGen;
    private SocketAddress _bindAddress;

    /* ------------------------------------------------------------ */
    /**
     * <p>Creates a WebSocketClient from a private WebSocketClientFactory.</p>
     * <p>This can be wasteful of resources if many clients are created.</p>
     *
     * @deprecated Use {@link WebSocketClientFactory#newWebSocketClient()}
     * @throws Exception if the private WebSocketClientFactory fails to start
     */
    @Deprecated
    public WebSocketClient() throws Exception
    {
        _factory=new WebSocketClientFactory();
        _factory.start();
        _maskGen=_factory.getMaskGen();
    }

    /* ------------------------------------------------------------ */
    /**
     * <p>Creates a WebSocketClient with shared WebSocketClientFactory.</p>
     *
     * @param factory the shared {@link WebSocketClientFactory}
     */
    public WebSocketClient(WebSocketClientFactory factory)
    {
        _factory=factory;
        _maskGen=_factory.getMaskGen();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The WebSocketClientFactory this client was created with.
     */
    public WebSocketClientFactory getFactory()
    {
        return _factory;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the address to bind the socket channel to
     * @see #setBindAddress(SocketAddress)
     */
    public SocketAddress getBindAddress()
    {
        return _bindAddress;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param bindAddress the address to bind the socket channel to
     * @see #getBindAddress()
     */
    public void setBindAddress(SocketAddress bindAddress)
    {
        this._bindAddress = bindAddress;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The maxIdleTime in ms for connections opened by this client,
     * or -1 if the default from {@link WebSocketClientFactory#getSelectorManager()} is used.
     * @see #setMaxIdleTime(int)
     */
    public int getMaxIdleTime()
    {
        return _maxIdleTime;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param maxIdleTime The max idle time in ms for connections opened by this client
     * @see #getMaxIdleTime()
     */
    public void setMaxIdleTime(int maxIdleTime)
    {
        _maxIdleTime=maxIdleTime;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The subprotocol string for connections opened by this client.
     * @see #setProtocol(String)
     */
    public String getProtocol()
    {
        return _protocol;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param protocol The subprotocol string for connections opened by this client.
     * @see #getProtocol()
     */
    public void setProtocol(String protocol)
    {
        _protocol = protocol;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The origin URI of the client
     * @see #setOrigin(String)
     */
    public String getOrigin()
    {
        return _origin;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param origin The origin URI of the client (eg "http://example.com")
     * @see #getOrigin()
     */
    public void setOrigin(String origin)
    {
        _origin = origin;
    }

    /* ------------------------------------------------------------ */
    /**
     * <p>Returns the map of the cookies that are sent during the initial HTTP handshake
     * that upgrades to the websocket protocol.</p>
     * @return The read-write cookie map
     */
    public Map<String,String> getCookies()
    {
        return _cookies;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The list of websocket protocol extensions
     */
    public List<String> getExtensions()
    {
        return _extensions;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the mask generator to use, or null if not mask generator should be used
     * @see #setMaskGen(MaskGen)
     */
    public MaskGen getMaskGen()
    {
        return _maskGen;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param maskGen the mask generator to use, or null if not mask generator should be used
     * @see #getMaskGen()
     */
    public void setMaskGen(MaskGen maskGen)
    {
        _maskGen = maskGen;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The initial maximum text message size (in characters) for a connection
     */
    public int getMaxTextMessageSize()
    {
        return _maxTextMessageSize;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the initial maximum text message size for a connection. This can be changed by
     * the application calling {@link WebSocket.Connection#setMaxTextMessageSize(int)}.
     * @param maxTextMessageSize The default maximum text message size (in characters) for a connection
     */
    public void setMaxTextMessageSize(int maxTextMessageSize)
    {
        _maxTextMessageSize = maxTextMessageSize;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The initial maximum binary message size (in bytes)  for a connection
     */
    public int getMaxBinaryMessageSize()
    {
        return _maxBinaryMessageSize;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the initial maximum binary message size for a connection. This can be changed by
     * the application calling {@link WebSocket.Connection#setMaxBinaryMessageSize(int)}.
     * @param maxBinaryMessageSize The default maximum binary message size (in bytes) for a connection
     */
    public void setMaxBinaryMessageSize(int maxBinaryMessageSize)
    {
        _maxBinaryMessageSize = maxBinaryMessageSize;
    }

    /* ------------------------------------------------------------ */
    /**
     * <p>Opens a websocket connection to the URI and blocks until the connection is accepted or there is an error.</p>
     *
     * @param uri The URI to connect to.
     * @param websocket The {@link WebSocket} instance to handle incoming events.
     * @param maxConnectTime The interval to wait for a successful connection
     * @param units the units of the maxConnectTime
     * @return A {@link WebSocket.Connection}
     * @throws IOException if the connection fails
     * @throws InterruptedException if the thread is interrupted
     * @throws TimeoutException if the timeout elapses before the connection is completed
     * @see #open(URI, WebSocket)
     */
    public WebSocket.Connection open(URI uri, WebSocket websocket,long maxConnectTime,TimeUnit units) throws IOException, InterruptedException, TimeoutException
    {
        try
        {
            return open(uri,websocket).get(maxConnectTime,units);
        }
        catch (ExecutionException e)
        {
            Throwable cause = e.getCause();
            if (cause instanceof IOException)
                throw (IOException)cause;
            if (cause instanceof Error)
                throw (Error)cause;
            if (cause instanceof RuntimeException)
                throw (RuntimeException)cause;
            throw new RuntimeException(cause);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * <p>Asynchronously opens a websocket connection and returns a {@link Future} to obtain the connection.</p>
     * <p>The caller must call {@link Future#get(long, TimeUnit)} if they wish to impose a connect timeout on the open.</p>
     *
     * @param uri The URI to connect to.
     * @param websocket The {@link WebSocket} instance to handle incoming events.
     * @return A {@link Future} to the {@link WebSocket.Connection}
     * @throws IOException if the connection fails
     * @see #open(URI, WebSocket, long, TimeUnit)
     */
    public Future<WebSocket.Connection> open(URI uri, WebSocket websocket) throws IOException
    {
        if (!_factory.isStarted())
            throw new IllegalStateException("Factory !started");

        InetSocketAddress address = toSocketAddress(uri);

        SocketChannel channel = null;
        try
        {
            channel = SocketChannel.open();
            if (_bindAddress != null)
                channel.socket().bind(_bindAddress);
            channel.socket().setTcpNoDelay(true);

            WebSocketFuture holder = new WebSocketFuture(websocket,uri,this,channel);

            channel.configureBlocking(false);
            channel.connect(address);
            _factory.getSelectorManager().register(channel,holder);

            return holder;
        }
        catch (RuntimeException e)
        {
            // close the channel (prevent connection leak)
            IO.close(channel);
            
            // rethrow
            throw e;
        }
        catch(IOException e)
        {
            // close the channel (prevent connection leak)
            IO.close(channel);

            // rethrow
            throw e;
        }
    }

    public static InetSocketAddress toSocketAddress(URI uri)
    {
        String scheme = uri.getScheme();
        if (!("ws".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme)))
            throw new IllegalArgumentException("Bad WebSocket scheme: " + scheme);
        int port = uri.getPort();
        if (port == 0)
            throw new IllegalArgumentException("Bad WebSocket port: " + port);
        if (port < 0)
            port = "ws".equals(scheme) ? 80 : 443;

        return new InetSocketAddress(uri.getHost(), port);
    }

    /* ------------------------------------------------------------ */
    /** The Future Websocket Connection.
     */
    static class WebSocketFuture implements Future<WebSocket.Connection>
    {
        final WebSocket _websocket;
        final URI _uri;
        final WebSocketClient _client;
        final CountDownLatch _done = new CountDownLatch(1);
        ByteChannel _channel;
        WebSocketConnection _connection;
        Throwable _exception;

        private WebSocketFuture(WebSocket websocket, URI uri, WebSocketClient client, ByteChannel channel)
        {
            _websocket=websocket;
            _uri=uri;
            _client=client;
            _channel=channel;
        }

        public void onConnection(WebSocketConnection connection)
        {
            try
            {
                _client.getFactory().addConnection(connection);

                connection.getConnection().setMaxTextMessageSize(_client.getMaxTextMessageSize());
                connection.getConnection().setMaxBinaryMessageSize(_client.getMaxBinaryMessageSize());

                WebSocketConnection con;
                synchronized (this)
                {
                    if (_channel!=null)
                        _connection=connection;
                    con=_connection;
                }

                if (con!=null)
                {
                    if (_websocket instanceof WebSocket.OnFrame)
                        ((WebSocket.OnFrame)_websocket).onHandshake((WebSocket.FrameConnection)con.getConnection());

                    _websocket.onOpen(con.getConnection());
                }
            }
            finally
            {
                _done.countDown();
            }
        }

        public void handshakeFailed(Throwable ex)
        {
            try
            {
                ByteChannel channel=null;
                synchronized (this)
                {
                    if (_channel!=null)
                    {
                        channel=_channel;
                        _channel=null;
                        _exception=ex;
                    }
                }

                if (channel!=null)
                {
                    if (ex instanceof ProtocolException)
                        closeChannel(channel,WebSocketConnectionRFC6455.CLOSE_PROTOCOL,ex.getMessage());
                    else
                        closeChannel(channel,WebSocketConnectionRFC6455.CLOSE_NO_CLOSE,ex.getMessage());
                }
            }
            finally
            {
                _done.countDown();
            }
        }

        public Map<String,String> getCookies()
        {
            return _client.getCookies();
        }

        public String getProtocol()
        {
            return _client.getProtocol();
        }

        public WebSocket getWebSocket()
        {
            return _websocket;
        }

        public URI getURI()
        {
            return _uri;
        }

        public int getMaxIdleTime()
        {
            return _client.getMaxIdleTime();
        }

        public String getOrigin()
        {
            return _client.getOrigin();
        }

        public MaskGen getMaskGen()
        {
            return _client.getMaskGen();
        }

        @Override
        public String toString()
        {
            return "[" + _uri + ","+_websocket+"]@"+hashCode();
        }

        public boolean cancel(boolean mayInterruptIfRunning)
        {
            try
            {
                ByteChannel channel=null;
                synchronized (this)
                {
                    if (_connection==null && _exception==null && _channel!=null)
                    {
                        channel=_channel;
                        _channel=null;
                    }
                }

                if (channel!=null)
                {
                    closeChannel(channel,WebSocketConnectionRFC6455.CLOSE_NO_CLOSE,"cancelled");
                    return true;
                }
                return false;
            }
            finally
            {
                _done.countDown();
            }
        }

        public boolean isCancelled()
        {
            synchronized (this)
            {
                return _channel==null && _connection==null;
            }
        }

        public boolean isDone()
        {
            synchronized (this)
            {
                return _connection!=null && _exception==null;
            }
        }

        public org.eclipse.jetty.websocket.WebSocket.Connection get() throws InterruptedException, ExecutionException
        {
            try
            {
                return get(Long.MAX_VALUE,TimeUnit.SECONDS);
            }
            catch(TimeoutException e)
            {
                throw new IllegalStateException("The universe has ended",e);
            }
        }

        public org.eclipse.jetty.websocket.WebSocket.Connection get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException,
                TimeoutException
        {
            _done.await(timeout,unit);
            ByteChannel channel=null;
            org.eclipse.jetty.websocket.WebSocket.Connection connection=null;
            Throwable exception;
            synchronized (this)
            {
                exception=_exception;
                if (_connection==null)
                {
                    exception=_exception;
                    channel=_channel;
                    _channel=null;
                }
                else
                    connection=_connection.getConnection();
            }

            if (channel!=null)
                closeChannel(channel,WebSocketConnectionRFC6455.CLOSE_NO_CLOSE,"timeout");
            if (exception!=null)
                throw new ExecutionException(exception);
            if (connection!=null)
                return connection;
            throw new TimeoutException();
        }

        private void closeChannel(ByteChannel channel,int code, String message)
        {
            try
            {
                _websocket.onClose(code,message);
            }
            catch(Exception e)
            {
                __log.warn(e);
            }

            try
            {
                channel.close();
            }
            catch(IOException e)
            {
                __log.debug(e);
            }
        }
    }
}
