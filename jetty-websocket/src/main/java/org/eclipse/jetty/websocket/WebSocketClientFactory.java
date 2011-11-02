package org.eclipse.jetty.websocket;

import java.io.EOFException;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Random;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.SimpleBuffers;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.io.nio.SelectorManager;
import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;


/* ------------------------------------------------------------ */
/**
 * <p>WebSocketClientFactory contains the common components needed by multiple {@link WebSocketClient} instances
 * (for example, a {@link ThreadPool}, a {@link SelectorManager NIO selector}, etc).</p>
 * <p>WebSocketClients with different configurations should share the same factory to avoid to waste resources.</p>
 * <p>If a ThreadPool or MaskGen is passed in the constructor, then it is not added with {@link AggregateLifeCycle#addBean(Object)},
 * so it's lifecycle must be controlled externally.  
 * @see WebSocketClient
 */
public class WebSocketClientFactory extends AggregateLifeCycle
{
    private final static Logger __log = org.eclipse.jetty.util.log.Log.getLogger(WebSocketClientFactory.class.getName());
    private final static Random __random = new Random();
    private final static ByteArrayBuffer __ACCEPT = new ByteArrayBuffer.CaseInsensitive("Sec-WebSocket-Accept");

    private final ThreadPool _threadPool;
    private final WebSocketClientSelector _selector;
    private MaskGen _maskGen;
    private WebSocketBuffers _buffers;

    /* ------------------------------------------------------------ */
    /**
     * <p>Creates a WebSocketClientFactory with the default configuration.</p>
     */
    public WebSocketClientFactory()
    {
        _threadPool=new QueuedThreadPool();
        addBean(_threadPool);
        _buffers=new WebSocketBuffers(8*1024);
        addBean(_buffers);
        _maskGen=new RandomMaskGen();
        addBean(_maskGen);
        _selector=new WebSocketClientSelector();
        addBean(_selector);
    }

    /* ------------------------------------------------------------ */
    /**
     * <p>Creates a WebSocketClientFactory with the given ThreadPool and the default configuration.</p>
     * @param threadPool the ThreadPool instance to use
     */
    public WebSocketClientFactory(ThreadPool threadPool)
    {
        _threadPool=threadPool;
        addBean(threadPool);
        _buffers=new WebSocketBuffers(8*1024);
        addBean(_buffers);
        _maskGen=new RandomMaskGen();
        addBean(_maskGen);
        _selector=new WebSocketClientSelector();
        addBean(_selector);
    }

    /* ------------------------------------------------------------ */
    /**
     * <p>Creates a WebSocketClientFactory with the specified configuration.</p>
     * @param threadPool the ThreadPool instance to use
     * @param maskGen the mask generator to use
     * @param bufferSize the read buffer size
     */
    public WebSocketClientFactory(ThreadPool threadPool,MaskGen maskGen,int bufferSize)
    {
        _threadPool=threadPool;
        addBean(threadPool);
        _buffers=new WebSocketBuffers(bufferSize);
        addBean(_buffers);
        _maskGen=maskGen;
        _selector=new WebSocketClientSelector();
        addBean(_selector);
    }

    /* ------------------------------------------------------------ */
    /**
     * Get the selectorManager. Used to configure the manager.
     * @return The {@link SelectorManager} instance.
     */
    public SelectorManager getSelectorManager()
    {
        return _selector;
    }

    /* ------------------------------------------------------------ */
    /** Get the ThreadPool.
     * Used to set/query the thread pool configuration.
     * @return The {@link ThreadPool}
     */
    public ThreadPool getThreadPool()
    {
        return _threadPool;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the shared mask generator, or null if no shared mask generator is used
     * @see {@link WebSocketClient#getMaskGen()}
     */
    public MaskGen getMaskGen()
    {
        return _maskGen;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param maskGen the shared mask generator, or null if no shared mask generator is used
     * @see {@link WebSocketClient#setMaskGen(MaskGen)}
     */
    public void setMaskGen(MaskGen maskGen)
    {
        if (isRunning())
            throw new IllegalStateException(getState());
        if (removeBean(_maskGen))
            addBean(maskGen);
        _maskGen=maskGen;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param bufferSize the read buffer size
     * @see #getBufferSize()
     */
    public void setBufferSize(int bufferSize)
    {
        if (isRunning())
            throw new IllegalStateException(getState());
        removeBean(_buffers);
        _buffers=new WebSocketBuffers(bufferSize);
        addBean(_buffers);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the read buffer size
     */
    public int getBufferSize()
    {
        return _buffers.getBufferSize();
    }

    /* ------------------------------------------------------------ */
    /**
     * <p>Creates and returns a new instance of a {@link WebSocketClient}, configured with this
     * WebSocketClientFactory instance.</p>
     *
     * @return a new {@link WebSocketClient} instance
     */
    public WebSocketClient newWebSocketClient()
    {
        return new WebSocketClient(this);
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        if (getThreadPool() instanceof LifeCycle && !((LifeCycle)getThreadPool()).isStarted())
            ((LifeCycle)getThreadPool()).start();
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
    }

    /* ------------------------------------------------------------ */
    /** WebSocket Client Selector Manager
     */
    class WebSocketClientSelector extends SelectorManager
    {
        @Override
        public boolean dispatch(Runnable task)
        {
            return _threadPool.dispatch(task);
        }

        @Override
        protected SelectChannelEndPoint newEndPoint(SocketChannel channel, SelectSet selectSet, final SelectionKey sKey) throws IOException
        {
            return new SelectChannelEndPoint(channel,selectSet,sKey);
        }

        @Override
        protected Connection newConnection(SocketChannel channel, SelectChannelEndPoint endpoint)
        {
            WebSocketClient.WebSocketFuture holder = (WebSocketClient.WebSocketFuture) endpoint.getSelectionKey().attachment();
            return new HandshakeConnection(endpoint,holder);
        }

        @Override
        protected void endPointOpened(SelectChannelEndPoint endpoint)
        {
            // TODO expose on outer class ??
        }

        @Override
        protected void endPointUpgraded(ConnectedEndPoint endpoint, Connection oldConnection)
        {
            throw new IllegalStateException();
        }

        @Override
        protected void endPointClosed(SelectChannelEndPoint endpoint)
        {
            endpoint.getConnection().closed();
        }

        @Override
        protected void connectionFailed(SocketChannel channel, Throwable ex, Object attachment)
        {
            if (!(attachment instanceof WebSocketClient.WebSocketFuture))
                super.connectionFailed(channel,ex,attachment);
            else
            {
                __log.debug(ex);
                WebSocketClient.WebSocketFuture future = (WebSocketClient.WebSocketFuture)attachment;

                future.handshakeFailed(ex);
            }
        }
    }


    /* ------------------------------------------------------------ */
    /** Handshake Connection.
     * Handles the connection until the handshake succeeds or fails.
     */
    class HandshakeConnection extends AbstractConnection
    {
        private final SelectChannelEndPoint _endp;
        private final WebSocketClient.WebSocketFuture _future;
        private final String _key;
        private final HttpParser _parser;
        private String _accept;
        private String _error;

        public HandshakeConnection(SelectChannelEndPoint endpoint, WebSocketClient.WebSocketFuture future)
        {
            super(endpoint,System.currentTimeMillis());
            _endp=endpoint;
            _future=future;

            byte[] bytes=new byte[16];
            __random.nextBytes(bytes);
            _key=new String(B64Code.encode(bytes));


            Buffers buffers = new SimpleBuffers(_buffers.getBuffer(),null);
            _parser=new HttpParser(buffers,_endp,

            new HttpParser.EventHandler()
            {
                @Override
                public void startResponse(Buffer version, int status, Buffer reason) throws IOException
                {
                    if (status!=101)
                    {
                        _error="Bad response status "+status+" "+reason;
                        _endp.close();
                    }
                }

                @Override
                public void parsedHeader(Buffer name, Buffer value) throws IOException
                {
                    if (__ACCEPT.equals(name))
                        _accept=value.toString();
                }

                @Override
                public void startRequest(Buffer method, Buffer url, Buffer version) throws IOException
                {
                    if (_error==null)
                        _error="Bad response: "+method+" "+url+" "+version;
                    _endp.close();
                }

                @Override
                public void content(Buffer ref) throws IOException
                {
                    if (_error==null)
                        _error="Bad response. "+ref.length()+"B of content?";
                    _endp.close();
                }
            });

            String path=_future.getURI().getPath();
            if (path==null || path.length()==0) 
            {
                path="/";
            }
            
            if(_future.getURI().getRawQuery() != null)
            {
                path += "?" + _future.getURI().getRawQuery();
            }

            String origin = future.getOrigin();

            StringBuilder request = new StringBuilder(512);
            request
                .append("GET ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(future.getURI().getHost()).append(":").append(_future.getURI().getPort()).append("\r\n")
                .append("Upgrade: websocket\r\n")
                .append("Connection: Upgrade\r\n")
                .append("Sec-WebSocket-Key: ")
                .append(_key).append("\r\n");
            
            if(origin!=null)
                request.append("Origin: ").append(origin).append("\r\n");
                
            request.append("Sec-WebSocket-Version: ").append(WebSocketConnectionD13.VERSION).append("\r\n");

            if (future.getProtocol()!=null)
                request.append("Sec-WebSocket-Protocol: ").append(future.getProtocol()).append("\r\n");

            if (future.getCookies()!=null && future.getCookies().size()>0)
            {
                for (String cookie : future.getCookies().keySet())
                    request
                        .append("Cookie: ")
                        .append(QuotedStringTokenizer.quoteIfNeeded(cookie,HttpFields.__COOKIE_DELIM))
                        .append("=")
                        .append(QuotedStringTokenizer.quoteIfNeeded(future.getCookies().get(cookie),HttpFields.__COOKIE_DELIM))
                        .append("\r\n");
            }

            request.append("\r\n");

            // TODO extensions

            try
            {
                Buffer handshake = new ByteArrayBuffer(request.toString(),false);
                int len=handshake.length();
                if (len!=_endp.flush(handshake))
                    throw new IOException("incomplete");
            }
            catch(IOException e)
            {
                future.handshakeFailed(e);
            }

        }

        public Connection handle() throws IOException
        {
            while (_endp.isOpen() && !_parser.isComplete())
            {
                switch (_parser.parseAvailable())
                {
                    case -1:
                        _future.handshakeFailed(new IOException("Incomplete handshake response"));
                        return this;
                    case 0:
                        return this;
                    default:
                        break;
                }
            }
            if (_error==null)
            {
                if (_accept==null)
                    _error="No Sec-WebSocket-Accept";
                else if (!WebSocketConnectionD13.hashKey(_key).equals(_accept))
                    _error="Bad Sec-WebSocket-Accept";
                else
                {
                    Buffer header=_parser.getHeaderBuffer();
                    MaskGen maskGen=_future.getMaskGen();
                    WebSocketConnectionD13 connection = new WebSocketConnectionD13(_future.getWebSocket(),_endp,_buffers,System.currentTimeMillis(),_future.getMaxIdleTime(),_future.getProtocol(),null,10,maskGen);

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

        public boolean isIdle()
        {
            return false;
        }

        public boolean isSuspended()
        {
            return false;
        }

        public void closed()
        {
            if (_error!=null)
                _future.handshakeFailed(new ProtocolException(_error));
            else
                _future.handshakeFailed(new EOFException());
        }
    }
}
