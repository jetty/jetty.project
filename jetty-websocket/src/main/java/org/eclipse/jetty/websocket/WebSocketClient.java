package org.eclipse.jetty.websocket;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.URI;
import java.nio.channels.ByteChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/** WebSocket Client
 * <p>This WebSocket Client class can create multiple websocket connections to multiple destinations.
 * It uses the same {@link WebSocket} endpoint API as the server.
 * Simple usage is as follows: <pre>
 *   WebSocketClientFactory factory = new WebSocketClientFactory();
 *   factory.start();
 *   WebSocketClient client = factory.newClient();
 *   client.start();
 *
 *   WebSocket.Connection connection =  client.open(new URI("ws://127.0.0.1:8080/"),new WebSocket.OnTextMessage()
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
 *   }).get(5,TimeUnit.SECONDS);
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
    private MaskGen _maskGen;


    /* ------------------------------------------------------------ */
    /** Create a WebSocket Client with private factory.
     * <p>Creates a WebSocketClient from a private WebSocketClientFactory.  
     * This can be wasteful of resources if many clients are created.
     * @deprecated Use {@link WebSocketClientFactory}
     */
    public WebSocketClient() throws Exception
    {
        _factory=new WebSocketClientFactory();
        _factory.start();
        _maskGen=_factory.getMaskGen();
    }

    /* ------------------------------------------------------------ */
    /** Create a WebSocket Client with shared factory.
     * @param threadpool
     */
    public WebSocketClient(WebSocketClientFactory factory)
    {
        _factory=factory;
        _maskGen=_factory.getMaskGen();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return The factory this client was created with.
     */
    public WebSocketClientFactory getFactory()
    {
        return _factory;
    }
    
    /* ------------------------------------------------------------ */
    /** Get the maxIdleTime for connections opened by this client.
     * @return The maxIdleTime in ms, or -1 if the default from {@link #getSelectorManager()} is used.
     */
    public int getMaxIdleTime()
    {
        return _maxIdleTime;
    }

    /* ------------------------------------------------------------ */
    /** Set the maxIdleTime for connections opened by this client.
     * @param maxIdleTime max idle time in ms
     */
    public void setMaxIdleTime(int maxIdleTime)
    {
        _maxIdleTime=maxIdleTime;
    }

    /* ------------------------------------------------------------ */
    /** Get the subprotocol string for connections opened by this client.
     * @return The subprotocol
     */
    public String getProtocol()
    {
        return _protocol;
    }

    /* ------------------------------------------------------------ */
    /** Set the subprotocol string for connections opened by this client.
     * @param protocol The subprotocol
     */
    public void setProtocol(String protocol)
    {
        _protocol = protocol;
    }

    /* ------------------------------------------------------------ */
    /** Get the origin of the client
     * @return The clients Origin
     */
    public String getOrigin()
    {
        return _origin;
    }

    /* ------------------------------------------------------------ */
    /** Set the origin of the client
     * @param origin the origin of the client (eg "http://example.com")
     */
    public void setOrigin(String origin)
    {
        _origin = origin;
    }

    /* ------------------------------------------------------------ */
    public Map<String,String> getCookies()
    {
        return _cookies;
    }

    /* ------------------------------------------------------------ */
    public List<String> getExtensions()
    {
        return _extensions;
    }

    /* ------------------------------------------------------------ */
    public MaskGen getMaskGen()
    {
        return _maskGen;
    }

    /* ------------------------------------------------------------ */
    public void setMaskGen(MaskGen maskGen)
    {
        _maskGen = maskGen;
    }

    /* ------------------------------------------------------------ */
    /** Open a WebSocket connection.
     * Open a websocket connection to the URI and block until the connection is accepted or there is an error.
     * @param uri The URI to connect to.
     * @param websocket The {@link WebSocket} instance to handle incoming events.
     * @param maxConnectTime The interval to wait for a successful connection
     * @param units the units of the maxConnectTime
     * @return A {@link WebSocket.Connection}
     * @throws IOException
     * @throws InterruptedException
     * @throws TimeoutException
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
    /** Asynchronously open a websocket connection.
     * Open a websocket connection and return a {@link Future} to obtain the connection.
     * The caller must call {@link Future#get(long, TimeUnit)} if they wish to impose a connect timeout on the open.
     *
     * @param uri The URI to connect to.
     * @param websocket The {@link WebSocket} instance to handle incoming events.
     * @return A {@link Future} to the {@link WebSocket.Connection}
     * @throws IOException
     */
    public Future<WebSocket.Connection> open(URI uri, WebSocket websocket) throws IOException
    {
        if (!_factory.isStarted())
            throw new IllegalStateException("Factory !started");
        String scheme=uri.getScheme();
        if (!("ws".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme)))
            throw new IllegalArgumentException("Bad WebSocket scheme '"+scheme+"'");
        if ("wss".equalsIgnoreCase(scheme))
            throw new IOException("wss not supported");

        SocketChannel channel = SocketChannel.open();
        channel.socket().setTcpNoDelay(true);
        int maxIdleTime = getMaxIdleTime();
        if (maxIdleTime<0)
            maxIdleTime=(int)_factory.getSelectorManager().getMaxIdleTime();
        if (maxIdleTime>0)
            channel.socket().setSoTimeout(maxIdleTime);

        InetSocketAddress address=new InetSocketAddress(uri.getHost(),uri.getPort());

        final WebSocketFuture holder=new WebSocketFuture(websocket,uri,_protocol,_origin,_maskGen,maxIdleTime,_cookies,_extensions,channel);

        channel.configureBlocking(false);
        channel.connect(address);
        _factory.getSelectorManager().register( channel, holder);

        return holder;
    }


    /* ------------------------------------------------------------ */
    /** The Future Websocket Connection.
     */
    static class WebSocketFuture implements Future<WebSocket.Connection>
    {
        final WebSocket _websocket;;
        final URI _uri;
        final String _protocol;
        final String _origin;
        final MaskGen _maskGen;
        final int _maxIdleTime;
        final Map<String,String> _cookies;
        final List<String> _extensions;
        final CountDownLatch _done = new CountDownLatch(1);

        ByteChannel _channel;
        WebSocketConnection _connection;
        Throwable _exception;

        private WebSocketFuture(WebSocket websocket, URI uri, String protocol, String origin, MaskGen maskGen, int maxIdleTime, Map<String,String> cookies,List<String> extensions, ByteChannel channel)
        {
            _websocket=websocket;
            _uri=uri;
            _protocol=protocol;
            _origin=origin;
            _maskGen=maskGen;
            _maxIdleTime=maxIdleTime;
            _cookies=cookies;
            _extensions=extensions;
            _channel=channel;
        }

        public void onConnection(WebSocketConnection connection)
        {
            try
            {
                synchronized (this)
                {
                    if (_channel!=null)
                        _connection=connection;
                }

                if (_connection!=null)
                {
                    if (_websocket instanceof WebSocket.OnFrame)
                        ((WebSocket.OnFrame)_websocket).onHandshake((WebSocket.FrameConnection)connection.getConnection());

                    _websocket.onOpen(connection.getConnection());

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
                        closeChannel(channel,WebSocketConnectionD12.CLOSE_PROTOCOL,ex.getMessage());
                    else
                        closeChannel(channel,WebSocketConnectionD12.CLOSE_NOCLOSE,ex.getMessage());
                }
            }
            finally
            {
                _done.countDown();
            }
        }

        public Map<String,String> getCookies()
        {
            return _cookies;
        }

        public String getProtocol()
        {
            return _protocol;
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
            return _maxIdleTime;
        }

        public String getOrigin()
        {
            return _origin;
        }

        public MaskGen getMaskGen()
        {
            return _maskGen;
        }
        
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
                    closeChannel(channel,WebSocketConnectionD12.CLOSE_NOCLOSE,"cancelled");
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
            Throwable exception=null;
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
                closeChannel(channel,WebSocketConnectionD12.CLOSE_NOCLOSE,"timeout");
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
