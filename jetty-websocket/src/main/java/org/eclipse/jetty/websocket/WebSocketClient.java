package org.eclipse.jetty.websocket;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.ByteChannel;
import java.nio.channels.SelectionKey;
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
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.eclipse.jetty.util.thread.Timeout;

public class WebSocketClient extends AggregateLifeCycle
{   
    private final static Logger __log = org.eclipse.jetty.util.log.Log.getLogger(WebSocketClient.class.getCanonicalName());
    private final static Random __random = new Random();
    private final static ByteArrayBuffer __ACCEPT = new ByteArrayBuffer.CaseInsensitive("Sec-WebSocket-Accept");

    private final WebSocketClient _root;
    private final WebSocketClient _parent;
    private final ThreadPool _threadPool;
    private final Selector _selector;
    private final Timeout _connectQ;

    private final Map<String,String> _cookies=new ConcurrentHashMap<String, String>();
    private final List<String> _extensions=new CopyOnWriteArrayList<String>();
    
    
    private int _bufferSize=64*1024;
    private String _protocol;
    private int _maxIdleTime=-1;
    
    private WebSocketBuffers _buffers;
    

    public WebSocketClient()
    {
        this(new QueuedThreadPool());
    }
    
    public WebSocketClient(ThreadPool threadpool)
    {
        _root=this;
        _parent=null;
        _threadPool=threadpool;
        _selector=new Selector();
        _connectQ=new Timeout();
        addBean(_selector);
        addBean(_threadPool);
    }

    public WebSocketClient(WebSocketClient parent)
    {
        _root=parent._root;
        _parent=parent;
        _threadPool=parent._threadPool;
        _selector=parent._selector;
        _connectQ=new Timeout();
        _parent.addBean(this);
    }
    
    public SelectorManager getSelectorManager()
    {
        return _selector;
    }
    
    public ThreadPool getThreadPool()
    {
        return _threadPool;
    }
    
    public int getMaxIdleTime()
    {
        return _maxIdleTime;
    }

    public void setMaxIdleTime(int maxIdleTime)
    {
        _maxIdleTime=maxIdleTime;
    }

    public int getBufferSize()
    {
        return _bufferSize;
    }

    public void setBufferSize(int bufferSize)
    {
        if (isRunning())
            throw new IllegalStateException(getState());
        _bufferSize = bufferSize;
    }
    
    public String getProtocol()
    {
        return _protocol;
    }

    public void setProtocol(String protocol)
    {
        _protocol = protocol;
    }

    @Override
    protected void doStart() throws Exception
    {
        if (_parent!=null && !_parent.isRunning())
            throw new IllegalStateException("parent:"+getState());
        
        _buffers = new WebSocketBuffers(_bufferSize); 

        super.doStart();
        
        // Start a selector and timer if this is the root client
        if (_parent==null)
        {
            for (int i=0;i<_selector.getSelectSets();i++)
            {
                final int id=i;
                _threadPool.dispatch(new Runnable()
                {
                    public void run()
                    {
                        while(isRunning())
                        {
                            try
                            {
                                _selector.doSelect(id);
                            }
                            catch (IOException e)
                            {
                                __log.warn(e);
                            }
                        }
                    }
                });
            }

            _connectQ.setDuration(0);

            _threadPool.dispatch(new Runnable()
            {
                public void run()
                {
                    while(isRunning())
                    {
                        try
                        {
                            Thread.sleep(200); // TODO configure?
                            _connectQ.tick(System.currentTimeMillis());
                        }
                        catch(InterruptedException e)
                        {
                            if (isRunning())
                                __log.warn(e);
                            else
                                __log.ignore(e);
                        }
                        catch(Exception e)
                        {
                            __log.warn(e);
                        }
                    }
                }
            });
        }
    }

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
            throw new RuntimeException(cause);
        }
    }
    
    public Future<WebSocket.Connection> open(URI uri, WebSocket websocket) throws IOException
    {
        if (!isStarted())
            throw new IllegalStateException("!started");
        String scheme=uri.getScheme();
        if (!("ws".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme)))
            throw new IllegalArgumentException("Bad WebSocket scheme '"+scheme+"'");
        if ("wss".equalsIgnoreCase(scheme))
            throw new IOException("wss not supported");
        
        SocketChannel channel = SocketChannel.open();
        channel.socket().setTcpNoDelay(true);
        int maxIdleTime = getMaxIdleTime();
        if (maxIdleTime<0)
            maxIdleTime=(int)_selector.getMaxIdleTime();
        if (maxIdleTime>0)
            channel.socket().setSoTimeout(maxIdleTime);

        InetSocketAddress address=new InetSocketAddress(uri.getHost(),uri.getPort());

        final WebSocketHolder holder=new WebSocketHolder(websocket,uri,_protocol,maxIdleTime,_cookies,_extensions,channel);

        channel.configureBlocking(false);
        channel.connect(address);
        _selector.register( channel, holder);

        return holder;
    }
   
    
    class Selector extends SelectorManager
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
            WebSocketHolder holder = (WebSocketHolder) endpoint.getSelectionKey().attachment();
            return new HandshakeConnection(endpoint,holder);
        }
        
        @Override
        protected void endPointOpened(SelectChannelEndPoint endpoint)
        {
            // TODO expose on outer class
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
            if (!(attachment instanceof WebSocketHolder))
                super.connectionFailed(channel,ex,attachment);
            else
            {
                __log.debug(ex);
                WebSocketHolder holder = (WebSocketHolder)attachment;
                
                holder.handshakeFailed(ex);
            } 
        }
    }
    
    class HandshakeConnection extends AbstractConnection
    {
        private final SelectChannelEndPoint _endp;
        private final WebSocketHolder _holder;
        private final String _key;
        private final HttpParser _parser;
        private String _accept;
        private String _error;
        
        
        public HandshakeConnection(SelectChannelEndPoint endpoint, WebSocketHolder holder)
        {
            super(endpoint,System.currentTimeMillis());
            _endp=endpoint;
            _holder=holder;
            
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
            
            String path=_holder.getURI().getPath();
            if (path==null || path.length()==0)
                path="/";
            
            String request=
                "GET "+path+" HTTP/1.1\r\n"+
                "Host: "+holder.getURI().getHost()+":"+_holder.getURI().getPort()+"\r\n"+
                "Upgrade: websocket\r\n"+
                "Connection: Upgrade\r\n"+
                "Sec-WebSocket-Key: "+_key+"\r\n"+
                "Sec-WebSocket-Origin: http://example.com\r\n"+
                "Sec-WebSocket-Version: "+WebSocketConnectionD10.VERSION+"\r\n";
            
            if (holder.getProtocol()!=null)
                request+="Sec-WebSocket-Protocol: "+holder.getProtocol()+"\r\n";
                
            if (holder.getCookies()!=null && holder.getCookies().size()>0)
            {
                for (String cookie : holder.getCookies().keySet())
                    request+="Cookie: "+QuotedStringTokenizer.quoteIfNeeded(cookie,HttpFields.__COOKIE_DELIM)+
                    "="+
                    QuotedStringTokenizer.quoteIfNeeded(holder.getCookies().get(cookie),HttpFields.__COOKIE_DELIM)+
                    "\r\n";
            }

            request+="\r\n";
            
            // TODO extensions
            
            try
            {
                Buffer handshake = new ByteArrayBuffer(request,false);
                int len=handshake.length();
                if (len!=_endp.flush(handshake))
                    throw new IOException("incomplete");
            }
            catch(IOException e)
            {
                holder.handshakeFailed(e);
            }
            
        }

        public Connection handle() throws IOException
        {
            while (_endp.isOpen() && !_parser.isComplete())
            {
                switch (_parser.parseAvailable())
                {
                    case -1:
                        _holder.handshakeFailed(new IOException("Incomplete handshake response"));
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
                else if (!WebSocketConnectionD10.hashKey(_key).equals(_accept))
                    _error="Bad Sec-WebSocket-Accept";
                else 
                {
                    Buffer header=_parser.getHeaderBuffer();
                    WebSocketConnectionD10 connection = new WebSocketConnectionD10(_holder.getWebSocket(),_endp,_buffers,System.currentTimeMillis(),_holder.getMaxIdleTime(),_holder.getProtocol(),null,10, new WebSocketGeneratorD10.RandomMaskGen());

                    if (header.hasContent())
                        connection.fillBuffersFrom(header);
                    _buffers.returnBuffer(header);

                    _holder.onConnection(connection);
                    
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
                _holder.handshakeFailed(new ProtocolException(_error));
            else
                _holder.handshakeFailed(new EOFException());
        }
    }

    class ProtocolException extends IOException
    {
        ProtocolException(String reason)
        {
            super(reason);
        }
    }

    class WebSocketHolder implements Future<WebSocket.Connection>
    {
        final WebSocket _websocket;;
        final URI _uri;
        final String _protocol;
        final int _maxIdleTime;
        final Map<String,String> _cookies;
        final List<String> _extensions;
        final CountDownLatch _latch = new CountDownLatch(1);

        ByteChannel _channel;
        WebSocketConnection _connection;
        Throwable _exception;
        
        final Timeout.Task _timeout = new Timeout.Task()
        {
            @Override
            public void expired()
            {
                handshakeFailed(new IOException("expired"));
            }
        };
        
        public WebSocketHolder(WebSocket websocket, URI uri, String protocol, int maxIdleTime, Map<String,String> cookies,List<String> extensions, ByteChannel channel)
        {
            _websocket=websocket;
            _uri=uri;
            _protocol=protocol;
            _maxIdleTime=maxIdleTime;
            _cookies=cookies;
            _extensions=extensions;
            _channel=channel;
        }
        
        public void onConnection(WebSocketConnection connection)
        {   
            try
            {
                _timeout.cancel();

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
                _latch.countDown();
            }
        }

        public void handshakeFailed(Throwable ex)
        {  
            try
            {
                _timeout.cancel();
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
                        closeChannel(channel,WebSocketConnectionD10.CLOSE_PROTOCOL,ex.getMessage());
                    else
                        closeChannel(channel,WebSocketConnectionD10.CLOSE_NOCLOSE,ex.getMessage());
                }
            }
            finally
            {
                _latch.countDown();
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
                    closeChannel(channel,WebSocketConnectionD10.CLOSE_NOCLOSE,"cancelled");
                    return true;
                }
                return false;
            }
            finally
            {
                _latch.countDown();
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
            _latch.await(timeout,unit);
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
                closeChannel(channel,WebSocketConnectionD10.CLOSE_NOCLOSE,"timeout");
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
