package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.PathMap;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.IndirectNIOBuffer;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.io.nio.SelectorManager;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.HostMap;
import org.eclipse.jetty.util.IPAddressMap;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ThreadPool;

/**
 * <p>Implementation of a tunneling proxy that supports HTTP CONNECT and transparent proxy.</p>
 * <p>To work as CONNECT proxy, objects of this class must be instantiated using the no-arguments
 * constructor, since the remote server information will be present in the CONNECT URI.</p>
 * <p>To work as transparent proxy, objects of this class must be instantiated using the string
 * argument constructor, passing the remote host address and port in the form {@code host:port}.</p>
 *
 * @version $Revision$ $Date$
 */
public class ProxyHandler extends HandlerWrapper
{
    private final Logger _logger = Log.getLogger(getClass().getName());
    private final SelectorManager _selectorManager = new Manager();
    private volatile int _connectTimeout = 5000;
    private volatile int _writeTimeout = 30000;
    private volatile ThreadPool _threadPool;
    private volatile boolean _privateThreadPool;
    private HostMap<PathMap> _white = new HostMap<PathMap>();
    private HostMap<PathMap> _black = new HostMap<PathMap>();

    public ProxyHandler()
    {
        this(null);
    }

    public ProxyHandler(Handler handler)
    {
        setHandler(handler);
    }

    public ProxyHandler(Handler handler, String[] white, String[] black)
    {
        setHandler(handler);
        set(white, _white);
        set(black, _black);
    }

    /**
     * @return the timeout, in milliseconds, to connect to the remote server
     */
    public int getConnectTimeout()
    {
        return _connectTimeout;
    }

    /**
     * @param connectTimeout the timeout, in milliseconds, to connect to the remote server
     */
    public void setConnectTimeout(int connectTimeout)
    {
        _connectTimeout = connectTimeout;
    }

    /**
     * @return the timeout, in milliseconds, to write data to a peer
     */
    public int getWriteTimeout()
    {
        return _writeTimeout;
    }

    /**
     * @param writeTimeout the timeout, in milliseconds, to write data to a peer
     */
    public void setWriteTimeout(int writeTimeout)
    {
        _writeTimeout = writeTimeout;
    }

    @Override
    public void setServer(Server server)
    {
        super.setServer(server);

        server.getContainer().update(this,null,_selectorManager,"selectManager");

        if (_privateThreadPool)
            server.getContainer().update(this,null,_privateThreadPool,"threadpool",true);
        else
            _threadPool=server.getThreadPool();
    }

    /**
     * @return the thread pool
     */
    public ThreadPool getThreadPool()
    {
        return _threadPool;
    }

    /**
     * @param threadPool the thread pool
     */
    public void setThreadPool(ThreadPool threadPool)
    {
        if (getServer()!=null)
            getServer().getContainer().update(this,_privateThreadPool?_threadPool:null,threadPool,"threadpool",true);
        _privateThreadPool=threadPool!=null;
        _threadPool=threadPool;
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();

        if (_threadPool==null)
        {
            _threadPool=getServer().getThreadPool();
            _privateThreadPool=false;
        }
        if (_threadPool instanceof LifeCycle && !((LifeCycle)_threadPool).isRunning())
            ((LifeCycle)_threadPool).start();

        _selectorManager.start();
        _threadPool.dispatch(new Runnable()
        {
            public void run()
            {
                while (isRunning())
                {
                    try
                    {
                        _selectorManager.doSelect(0);
                    }
                    catch (IOException x)
                    {
                        _logger.warn("Unexpected exception", x);
                    }
                }
            }
        });
    }

    @Override
    protected void doStop() throws Exception
    {
        _selectorManager.stop();

        ThreadPool threadPool = _threadPool;
        if (_privateThreadPool && _threadPool != null && threadPool instanceof LifeCycle)
            ((LifeCycle)threadPool).stop();

        super.doStop();
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        if (HttpMethods.CONNECT.equalsIgnoreCase(request.getMethod()))
        {
            _logger.debug("CONNECT request for {}", request.getRequestURI());
            handleConnect(baseRequest, request, response, request.getRequestURI());
        }
        else
        {
            super.handle(target, baseRequest, request, response);
        }
    }

    /**
     * <p>Handles a CONNECT request.</p>
     * <p>CONNECT requests may have authentication headers such as <code>Proxy-Authorization</code>
     * that authenticate the client with the proxy.</p>
     *
     * @param baseRequest Jetty-specific http request
     * @param request the http request
     * @param response the http response
     * @param serverAddress the remote server address in the form {@code host:port}
     * @throws ServletException if an application error occurs
     * @throws IOException if an I/O error occurs
     */
    protected void handleConnect(Request baseRequest, HttpServletRequest request, HttpServletResponse response, String serverAddress) throws ServletException, IOException
    {
        boolean proceed = handleAuthentication(request, response, serverAddress);
        if (!proceed)
            return;

        String host = serverAddress;
        int port = 80;
        int colon = serverAddress.indexOf(':');
        if (colon > 0)
        {
            host = serverAddress.substring(0, colon);
            port = Integer.parseInt(serverAddress.substring(colon + 1));
        }
        
        String uri = request.getRequestURI();
        if (validateDestination(host, uri))
        {
            throw new ServletException("Forbidden: "+host+uri);
        }

        SocketChannel channel = connectToServer(request, host, port);

        // Transfer unread data from old connection to new connection
        // We need to copy the data to avoid races:
        // 1. when this unread data is written and the server replies before the clientToProxy
        // connection is installed (it is only installed after returning from this method)
        // 2. when the client sends data before this unread data has been written.
        HttpConnection httpConnection = HttpConnection.getCurrentConnection();
        Buffer headerBuffer = ((HttpParser)httpConnection.getParser()).getHeaderBuffer();
        Buffer bodyBuffer = ((HttpParser)httpConnection.getParser()).getBodyBuffer();
        int length = headerBuffer == null ? 0 : headerBuffer.length();
        length += bodyBuffer == null ? 0 : bodyBuffer.length();
        IndirectNIOBuffer buffer = null;
        if (length > 0)
        {
            buffer = new IndirectNIOBuffer(length);
            if (headerBuffer != null)
            {
                buffer.put(headerBuffer);
                headerBuffer.clear();
            }
            if (bodyBuffer != null)
            {
                buffer.put(bodyBuffer);
                bodyBuffer.clear();
            }
        }

        ConcurrentMap<String, Object> context = new ConcurrentHashMap<String, Object>();
        prepareContext(request, context);

        ClientToProxyConnection clientToProxy = prepareConnections(context, channel, buffer);

        // CONNECT expects a 200 response
        response.setStatus(HttpServletResponse.SC_OK);

        // Prevent close
        baseRequest.getConnection().getGenerator().setPersistent(true);

        // Close to force last flush it so that the client receives it
        response.getOutputStream().close();

        upgradeConnection(request, response, clientToProxy);
    }

    private ClientToProxyConnection prepareConnections(ConcurrentMap<String, Object> context, SocketChannel channel, Buffer buffer)
    {
        HttpConnection httpConnection = HttpConnection.getCurrentConnection();
        ProxyToServerConnection proxyToServer = newProxyToServerConnection(context, buffer);
        ClientToProxyConnection clientToProxy = newClientToProxyConnection(context, channel, httpConnection.getEndPoint(), httpConnection.getTimeStamp());
        clientToProxy.setConnection(proxyToServer);
        proxyToServer.setConnection(clientToProxy);
        return clientToProxy;
    }

    /**
     * <p>Handles the authentication before setting up the tunnel to the remote server.</p>
     * <p>The default implementation returns true.</p>
     *
     * @param request the HTTP request
     * @param response the HTTP response
     * @param address the address of the remote server in the form {@code host:port}.
     * @return true to allow to connect to the remote host, false otherwise
     * @throws ServletException to report a server error to the caller
     * @throws IOException to report a server error to the caller
     */
    protected boolean handleAuthentication(HttpServletRequest request, HttpServletResponse response, String address) throws ServletException, IOException
    {
        return true;
    }

    protected ClientToProxyConnection newClientToProxyConnection(ConcurrentMap<String, Object> context, SocketChannel channel, EndPoint endPoint, long timeStamp)
    {
        return new ClientToProxyConnection(context, channel, endPoint, timeStamp);
    }

    protected ProxyToServerConnection newProxyToServerConnection(ConcurrentMap<String, Object> context, Buffer buffer)
    {
        return new ProxyToServerConnection(context, buffer);
    }

    private SocketChannel connectToServer(HttpServletRequest request, String host, int port) throws IOException
    {
        SocketChannel channel = connect(request, host, port);
        channel.configureBlocking(false);
        return channel;
    }

    /**
     * <p>Establishes a connection to the remote server.</p>
     * @param request the HTTP request that initiated the tunnel
     * @param host the host to connect to
     * @param port the port to connect to
     * @return a {@link SocketChannel} connected to the remote server
     * @throws IOException if the connection cannot be established
     */
    protected SocketChannel connect(HttpServletRequest request, String host, int port) throws IOException
    {
        _logger.debug("Establishing connection to {}:{}", host, port);
        // Connect to remote server
        SocketChannel channel = SocketChannel.open();
        channel.socket().setTcpNoDelay(true);
        channel.socket().connect(new InetSocketAddress(host, port), getConnectTimeout());
        _logger.debug("Established connection to {}:{}", host, port);
        return channel;
    }

    protected void prepareContext(HttpServletRequest request, ConcurrentMap<String, Object> context)
    {
    }

    private void upgradeConnection(HttpServletRequest request, HttpServletResponse response, Connection connection) throws IOException
    {
        // Set the new connection as request attribute and change the status to 101
        // so that Jetty understands that it has to upgrade the connection
        request.setAttribute("org.eclipse.jetty.io.Connection", connection);
        response.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
        _logger.debug("Upgraded connection to {}", connection);
    }

    private void register(SocketChannel channel, ProxyToServerConnection proxyToServer) throws IOException
    {
        _selectorManager.register(channel, proxyToServer);
        proxyToServer.waitReady(_connectTimeout);
    }

    /**
     * <p>Reads (with non-blocking semantic) into the given {@code buffer} from the given {@code endPoint}.</p>
     * @param endPoint the endPoint to read from
     * @param buffer the buffer to read data into
     * @param context the context information related to the connection
     * @return the number of bytes read (possibly 0 since the read is non-blocking)
     * or -1 if the channel has been closed remotely
     * @throws IOException if the endPoint cannot be read
     */
    protected int read(EndPoint endPoint, Buffer buffer, ConcurrentMap<String, Object> context) throws IOException
    {
        return endPoint.fill(buffer);
    }

    /**
     * <p>Writes (with blocking semantic) the given buffer of data onto the given endPoint.</p>
     *
     * @param endPoint the endPoint to write to
     * @param buffer the buffer to write
     * @param context the context information related to the connection
     * @throws IOException if the buffer cannot be written
     * @return the number of bytes written
     */
    protected int write(EndPoint endPoint, Buffer buffer, ConcurrentMap<String, Object> context) throws IOException
    {
        if (buffer == null)
            return 0;

        int length = buffer.length();
        StringBuilder builder = new StringBuilder();
        int written = endPoint.flush(buffer);
        builder.append(written);
        buffer.compact();
        if (!endPoint.isBlocking())
        {
            while (buffer.space() == 0)
            {
                boolean ready = endPoint.blockWritable(getWriteTimeout());
                if (!ready)
                    throw new IOException("Write timeout");

                written = endPoint.flush(buffer);
                builder.append("+").append(written);
                buffer.compact();
            }
        }
        _logger.debug("Written {}/{} bytes {}", builder, length, endPoint);
        return length;
    }

    private class Manager extends SelectorManager
    {
        @Override
        protected SocketChannel acceptChannel(SelectionKey key) throws IOException
        {
            // This is a client-side selector manager
            throw new IllegalStateException();
        }

        @Override
        protected SelectChannelEndPoint newEndPoint(SocketChannel channel, SelectSet selectSet, SelectionKey selectionKey) throws IOException
        {
            return new SelectChannelEndPoint(channel, selectSet, selectionKey);
        }

        @Override
        protected Connection newConnection(SocketChannel channel, SelectChannelEndPoint endpoint)
        {
            ProxyToServerConnection proxyToServer = (ProxyToServerConnection)endpoint.getSelectionKey().attachment();
            proxyToServer.setTimeStamp(System.currentTimeMillis());
            proxyToServer.setEndPoint(endpoint);
            return proxyToServer;
        }

        @Override
        protected void endPointOpened(SelectChannelEndPoint endpoint)
        {
            ProxyToServerConnection proxyToServer = (ProxyToServerConnection)endpoint.getSelectionKey().attachment();
            proxyToServer.ready();
        }

        @Override
        public boolean dispatch(Runnable task)
        {
            return _threadPool.dispatch(task);
        }

        @Override
        protected void endPointClosed(SelectChannelEndPoint endpoint)
        {
        }

        @Override
        protected void endPointUpgraded(ConnectedEndPoint endpoint, Connection oldConnection)
        {
        }
    }

    public class ProxyToServerConnection implements Connection
    {
        private final CountDownLatch _ready = new CountDownLatch(1);
        private final Buffer _buffer = new IndirectNIOBuffer(1024);
        private final ConcurrentMap<String, Object> _context;
        private volatile Buffer _data;
        private volatile ClientToProxyConnection _toClient;
        private volatile long _timestamp;
        private volatile SelectChannelEndPoint _endPoint;

        public ProxyToServerConnection(ConcurrentMap<String, Object> context, Buffer data)
        {
            _context = context;
            _data = data;
        }

        public Connection handle() throws IOException
        {
            _logger.debug("ProxyToServer: begin reading from server");
            try
            {
                if (_data != null)
                {
                    int written = write(_endPoint, _data, _context);
                    _logger.debug("ProxyToServer: written to server {} bytes", written);
                    _data = null;
                }

                while (true)
                {
                    int read = read(_endPoint, _buffer, _context);

                    if (read == -1)
                    {
                        _logger.debug("ProxyToServer: server closed connection {}", _endPoint);
                        close();
                        break;
                    }

                    if (read == 0)
                        break;

                    _logger.debug("ProxyToServer: read from server {} bytes {}", read, _endPoint);
                    int written = write(_toClient._endPoint, _buffer, _context);
                    _logger.debug("ProxyToServer: written to client {} bytes", written);
                }
                return this;
            }
            catch (IOException x)
            {
                _logger.warn("ProxyToServer: Unexpected exception", x);
                close();
                throw x;
            }
            catch (RuntimeException x)
            {
                _logger.warn("ProxyToServer: Unexpected exception", x);
                close();
                throw x;
            }
            finally
            {
                _logger.debug("ProxyToServer: end reading from server");
            }
        }

        public void setConnection(ClientToProxyConnection connection)
        {
            _toClient = connection;
        }

        public long getTimeStamp()
        {
            return _timestamp;
        }

        public void setTimeStamp(long timestamp)
        {
            _timestamp = timestamp;
        }

        public void setEndPoint(SelectChannelEndPoint endpoint)
        {
            _endPoint = endpoint;
            _logger.debug("ProxyToServer: {}", _endPoint);
        }

        public boolean isIdle()
        {
            return false;
        }

        public boolean isSuspended()
        {
            return false;
        }

        public void ready()
        {
            _ready.countDown();
        }

        public void waitReady(long timeout) throws IOException
        {
            try
            {
                _ready.await(timeout, TimeUnit.MILLISECONDS);
            }
            catch (final InterruptedException x)
            {
                throw new IOException(){{initCause(x);}};
            }
        }

        public void closeClient() throws IOException
        {
            _toClient.closeClient();
        }

        public void closeServer() throws IOException
        {
            _endPoint.close();
        }

        public void close()
        {
            try
            {
                closeClient();
            }
            catch (IOException x)
            {
                _logger.debug("ProxyToServer: Unexpected exception closing the client", x);
            }

            try
            {
                closeServer();
            }
            catch (IOException x)
            {
                _logger.debug("ProxyToServer: Unexpected exception closing the server", x);
            }
        }
    }

    public class ClientToProxyConnection implements Connection
    {
        private final Buffer _buffer = new IndirectNIOBuffer(1024);
        private final ConcurrentMap<String, Object> _context;
        private final SocketChannel _channel;
        private final EndPoint _endPoint;
        private final long _timestamp;
        private volatile ProxyToServerConnection _toServer;
        private boolean _firstTime = true;

        public ClientToProxyConnection(ConcurrentMap<String, Object> context, SocketChannel channel, EndPoint endPoint, long timestamp)
        {
            _context = context;
            _channel = channel;
            _endPoint = endPoint;
            _timestamp = timestamp;
            _logger.debug("ClientToProxy: {}", _endPoint);
        }

        public Connection handle() throws IOException
        {
            _logger.debug("ClientToProxy: begin reading from client");
            try
            {
                if (_firstTime)
                {
                    _firstTime = false;
                    register(_channel, _toServer);
                    _logger.debug("ClientToProxy: registered channel {} with connection {}", _channel, _toServer);
                }

                while (true)
                {
                    int read = read(_endPoint, _buffer, _context);

                    if (read == -1)
                    {
                        _logger.debug("ClientToProxy: client closed connection {}", _endPoint);
                        close();
                        break;
                    }

                    if (read == 0)
                        break;

                    _logger.debug("ClientToProxy: read from client {} bytes {}", read, _endPoint);
                    int written = write(_toServer._endPoint, _buffer, _context);
                    _logger.debug("ClientToProxy: written to server {} bytes", written);
                }
                return this;
            }
            catch (ClosedChannelException x)
            {
                _logger.debug("ClientToProxy",x);
                closeServer();
                throw x;
            }
            catch (IOException x)
            {
                _logger.warn("ClientToProxy", x);
                close();
                throw x;
            }
            catch (RuntimeException x)
            {
                _logger.warn("ClientToProxy", x);
                close();
                throw x;
            }
            finally
            {
                _logger.debug("ClientToProxy: end reading from client");
            }
        }

        public long getTimeStamp()
        {
            return _timestamp;
        }

        public boolean isIdle()
        {
            return false;
        }

        public boolean isSuspended()
        {
            return false;
        }

        public void setConnection(ProxyToServerConnection connection)
        {
            _toServer = connection;
        }

        public void closeClient() throws IOException
        {
            _endPoint.close();
        }

        public void closeServer() throws IOException
        {
            _toServer.closeServer();
        }

        public void close()
        {
            try
            {
                closeClient();
            }
            catch (IOException x)
            {
                _logger.debug("ClientToProxy: Unexpected exception closing the client", x);
            }

            try
            {
                closeServer();
            }
            catch (IOException x)
            {
                _logger.debug("ClientToProxy: Unexpected exception closing the server", x);
            }
        }
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Add a whitelist entry to an existing handler configuration
     * 
     * @param entry new whitelist entry
     */
    public void addWhite(String entry)
    {
        add(entry, _white);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Add a blacklist entry to an existing handler configuration
     * 
     * @param entry new blacklist entry
     */
    public void addBlack(String entry)
    {
        add(entry, _black);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Re-initialize the whitelist of existing handler object
     * 
     * @param entries array of whitelist entries
     */
    public void setWhite(String[] entries)
    {
        set(entries, _white);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Re-initialize the blacklist of existing handler object
     * 
     * @param entries array of blacklist entries
     */
    public void setBlack(String[] entries)
    {
        set(entries, _black);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Helper method to process a list of new entries and replace 
     * the content of the specified host map
     * 
     * @param entries new entries
     * @param patternMap target host map
     */
    protected void set(String[] entries,  HostMap<PathMap> hostMap)
    {
        hostMap.clear();
        
        for (String addrPath:entries)
        {
            add(addrPath, hostMap);
        }
    }
  
    /* ------------------------------------------------------------ */
    /**
     * Helper method to parse the new entry and add it to 
     * the specified host map.
     * 
     * @param entry new entry
     * @param patternMap target host map
     */
    private void add(String entry, HostMap<PathMap> hostMap)
    {
        if (entry != null && entry.length() > 0)
        {
            int idx = entry.indexOf('/');
    
            String host = idx > 0 ? entry.substring(0,idx) : entry;        
            String path = idx > 0 ? entry.substring(idx) : "/*";
            
            host = host.trim();
            PathMap pathMap = hostMap.get(host);
            if (pathMap == null)
            {
                pathMap = new PathMap(true);
                hostMap.put(host,pathMap);
            }
            if (path != null)
                pathMap.put(path,path);
        }
    }
    
    public boolean validateDestination(String host, String path)
    {
        if (_white.size()>0)
        {
            boolean match = false;
            
            Object whiteObj = _white.getLazyMatches(host);
            if (whiteObj != null) 
            {
                List whiteList = (whiteObj instanceof List) ? (List)whiteObj : Collections.singletonList(whiteObj);

                for (Object entry: whiteList)
                {
                    PathMap pathMap = ((Map.Entry<String, PathMap>)entry).getValue();
                    if (match = (pathMap!=null && (pathMap.size()==0 || pathMap.match(path)!=null)))
                        break;
                }
            }

            if (!match)
                return false;
        }

        if (_black.size() > 0)
        {
            Object blackObj = _black.getLazyMatches(host);
            if (blackObj != null) 
            {
                List blackList = (blackObj instanceof List) ? (List)blackObj : Collections.singletonList(blackObj);
    
                for (Object entry: blackList)
                {
                    PathMap pathMap = ((Map.Entry<String, PathMap>)entry).getValue();
                    if (pathMap!=null && (pathMap.size()==0 || pathMap.match(path)!=null))
                        return false;
                }
            }
        }
        
        return true;
    }
}
