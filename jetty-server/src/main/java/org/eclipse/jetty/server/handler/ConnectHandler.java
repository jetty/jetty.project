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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.AsyncConnection;
import org.eclipse.jetty.io.nio.IndirectNIOBuffer;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.io.nio.SelectorManager;
import org.eclipse.jetty.server.AbstractHttpConnection;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.HostMap;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ThreadPool;

/**
 * <p>Implementation of a tunneling proxy that supports HTTP CONNECT.</p>
 * <p>To work as CONNECT proxy, objects of this class must be instantiated using the no-arguments
 * constructor, since the remote server information will be present in the CONNECT URI.</p>
 */
public class ConnectHandler extends HandlerWrapper
{
    private static final Logger LOG = Log.getLogger(ConnectHandler.class);
    private final SelectorManager _selectorManager = new Manager();
    private volatile int _connectTimeout = 5000;
    private volatile int _writeTimeout = 30000;
    private volatile ThreadPool _threadPool;
    private volatile boolean _privateThreadPool;
    private HostMap<String> _white = new HostMap<String>();
    private HostMap<String> _black = new HostMap<String>();

    public ConnectHandler()
    {
        this(null);
    }

    public ConnectHandler(String[] white, String[] black)
    {
        this(null, white, black);
    }

    public ConnectHandler(Handler handler)
    {
        setHandler(handler);
    }

    public ConnectHandler(Handler handler, String[] white, String[] black)
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

        server.getContainer().update(this, null, _selectorManager, "selectManager");

        if (_privateThreadPool)
            server.getContainer().update(this, null, _privateThreadPool, "threadpool", true);
        else
            _threadPool = server.getThreadPool();
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
        if (getServer() != null)
            getServer().getContainer().update(this, _privateThreadPool ? _threadPool : null, threadPool, "threadpool", true);
        _privateThreadPool = threadPool != null;
        _threadPool = threadPool;
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();

        if (_threadPool == null)
        {
            _threadPool = getServer().getThreadPool();
            _privateThreadPool = false;
        }
        if (_threadPool instanceof LifeCycle && !((LifeCycle)_threadPool).isRunning())
            ((LifeCycle)_threadPool).start();

        _selectorManager.start();
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
            LOG.debug("CONNECT request for {}", request.getRequestURI());
            try
            {
                handleConnect(baseRequest, request, response, request.getRequestURI());
            }
            catch(Exception e)
            {
                LOG.warn("ConnectHandler "+baseRequest.getUri()+" "+ e);
                LOG.debug(e);
            }
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
     * @param baseRequest   Jetty-specific http request
     * @param request       the http request
     * @param response      the http response
     * @param serverAddress the remote server address in the form {@code host:port}
     * @throws ServletException if an application error occurs
     * @throws IOException      if an I/O error occurs
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

        if (!validateDestination(host))
        {
            LOG.info("ProxyHandler: Forbidden destination " + host);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            baseRequest.setHandled(true);
            return;
        }

        SocketChannel channel;

        try
        {
            channel = connectToServer(request,host,port);
        }
        catch (SocketException se)
        {
            LOG.info("ConnectHandler: SocketException " + se.getMessage());
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            baseRequest.setHandled(true);
            return;
        }
        catch (SocketTimeoutException ste)
        {
            LOG.info("ConnectHandler: SocketTimeoutException" + ste.getMessage());
            response.setStatus(HttpServletResponse.SC_GATEWAY_TIMEOUT);
            baseRequest.setHandled(true);
            return;
        }
        catch (IOException ioe)
        {
            LOG.info("ConnectHandler: IOException" + ioe.getMessage());
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            baseRequest.setHandled(true);
            return;
        }

        // Transfer unread data from old connection to new connection
        // We need to copy the data to avoid races:
        // 1. when this unread data is written and the server replies before the clientToProxy
        // connection is installed (it is only installed after returning from this method)
        // 2. when the client sends data before this unread data has been written.
        AbstractHttpConnection httpConnection = AbstractHttpConnection.getCurrentConnection();
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
        AbstractHttpConnection httpConnection = AbstractHttpConnection.getCurrentConnection();
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
     * @param request  the HTTP request
     * @param response the HTTP response
     * @param address  the address of the remote server in the form {@code host:port}.
     * @return true to allow to connect to the remote host, false otherwise
     * @throws ServletException to report a server error to the caller
     * @throws IOException      to report a server error to the caller
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

    // may return null
    private SocketChannel connectToServer(HttpServletRequest request, String host, int port) throws IOException
    {
        SocketChannel channel = connect(request, host, port);      
        channel.configureBlocking(false);
        return channel;
    }

    /**
     * <p>Establishes a connection to the remote server.</p>
     *
     * @param request the HTTP request that initiated the tunnel
     * @param host    the host to connect to
     * @param port    the port to connect to
     * @return a {@link SocketChannel} connected to the remote server
     * @throws IOException if the connection cannot be established
     */
    protected SocketChannel connect(HttpServletRequest request, String host, int port) throws IOException
    {
        SocketChannel channel = SocketChannel.open();

        if (channel == null)
        {
            throw new IOException("unable to connect to " + host + ":" + port);
        }

        try
        {
            // Connect to remote server
            LOG.debug("Establishing connection to {}:{}", host, port);
            channel.socket().setTcpNoDelay(true);
            channel.socket().connect(new InetSocketAddress(host, port), getConnectTimeout());
            LOG.debug("Established connection to {}:{}", host, port);
            return channel;
        }
        catch (IOException x)
        {
            LOG.debug("Failed to establish connection to " + host + ":" + port, x);
            try
            {
                channel.close();
            }
            catch (IOException xx)
            {
                LOG.ignore(xx);
            }
            throw x;
        }
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
        LOG.debug("Upgraded connection to {}", connection);
    }

    private void register(SocketChannel channel, ProxyToServerConnection proxyToServer) throws IOException
    {
        _selectorManager.register(channel, proxyToServer);
        proxyToServer.waitReady(_connectTimeout);
    }

    /**
     * <p>Reads (with non-blocking semantic) into the given {@code buffer} from the given {@code endPoint}.</p>
     *
     * @param endPoint the endPoint to read from
     * @param buffer   the buffer to read data into
     * @param context  the context information related to the connection
     * @return the number of bytes read (possibly 0 since the read is non-blocking)
     *         or -1 if the channel has been closed remotely
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
     * @param buffer   the buffer to write
     * @param context  the context information related to the connection
     * @throws IOException if the buffer cannot be written
     * @return the number of bytes written
     */
    protected int write(EndPoint endPoint, Buffer buffer, ConcurrentMap<String, Object> context) throws IOException
    {
        if (buffer == null)
            return 0;

        int length = buffer.length();
        final StringBuilder debug = LOG.isDebugEnabled()?new StringBuilder():null;
        int flushed = endPoint.flush(buffer);
        if (debug!=null)
            debug.append(flushed);
        
        // Loop until all written
        while (buffer.length()>0 && !endPoint.isOutputShutdown())
        {
            if (!endPoint.isBlocking())
            {
                boolean ready = endPoint.blockWritable(getWriteTimeout());
                if (!ready)
                    throw new IOException("Write timeout");
            }
            flushed = endPoint.flush(buffer);
            if (debug!=null)
                debug.append("+").append(flushed);
        }
       
        LOG.debug("Written {}/{} bytes {}", debug, length, endPoint);
        buffer.compact();
        return length;
    }

    private class Manager extends SelectorManager
    {
        @Override
        protected SelectChannelEndPoint newEndPoint(SocketChannel channel, SelectSet selectSet, SelectionKey key) throws IOException
        {
            SelectChannelEndPoint endp = new SelectChannelEndPoint(channel, selectSet, key, channel.socket().getSoTimeout());
            endp.setConnection(selectSet.getManager().newConnection(channel,endp, key.attachment()));
            endp.setMaxIdleTime(_writeTimeout);
            return endp;
        }

        @Override
        public AsyncConnection newConnection(SocketChannel channel, AsyncEndPoint endpoint, Object attachment)
        {
            ProxyToServerConnection proxyToServer = (ProxyToServerConnection)attachment;
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

    public class ProxyToServerConnection implements AsyncConnection
    {
        private final CountDownLatch _ready = new CountDownLatch(1);
        private final Buffer _buffer = new IndirectNIOBuffer(4096);
        private final ConcurrentMap<String, Object> _context;
        private volatile Buffer _data;
        private volatile ClientToProxyConnection _toClient;
        private volatile long _timestamp;
        private volatile AsyncEndPoint _endPoint;

        public ProxyToServerConnection(ConcurrentMap<String, Object> context, Buffer data)
        {
            _context = context;
            _data = data;
        }

        @Override
        public String toString()
        {
            StringBuilder builder = new StringBuilder("ProxyToServer");
            builder.append("(:").append(_endPoint.getLocalPort());
            builder.append("<=>:").append(_endPoint.getRemotePort());
            return builder.append(")").toString();
        }

        public Connection handle() throws IOException
        {
            LOG.debug("{}: begin reading from server", this);
            try
            {
                writeData();

                while (true)
                {
                    int read = read(_endPoint, _buffer, _context);

                    if (read == -1)
                    {
                        LOG.debug("{}: server closed connection {}", this, _endPoint);

                        if (_endPoint.isOutputShutdown() || !_endPoint.isOpen())
                            closeClient();
                        else
                            _toClient.shutdownOutput();

                        break;
                    }

                    if (read == 0)
                        break;

                    LOG.debug("{}: read from server {} bytes {}", this, read, _endPoint);
                    int written = write(_toClient._endPoint, _buffer, _context);
                    LOG.debug("{}: written to {} {} bytes", this, _toClient, written);
                }
                return this;
            }
            catch (ClosedChannelException x)
            {
                LOG.debug(x);
                throw x;
            }
            catch (IOException x)
            {
                LOG.warn(this + ": unexpected exception", x);
                close();
                throw x;
            }
            catch (RuntimeException x)
            {
                LOG.warn(this + ": unexpected exception", x);
                close();
                throw x;
            }
            finally
            {
                LOG.debug("{}: end reading from server", this);
            }
        }

        public void onInputShutdown() throws IOException
        {
        }

        private void writeData() throws IOException
        {
            // This method is called from handle() and closeServer()
            // which may happen concurrently (e.g. a client closing
            // while reading from the server), so needs synchronization
            synchronized (this)
            {
                if (_data != null)
                {
                    try
                    {
                        int written = write(_endPoint, _data, _context);
                        LOG.debug("{}: written to server {} bytes", this, written);
                    }
                    finally
                    {
                        // Attempt once to write the data; if the write fails (for example
                        // because the connection is already closed), clear the data and
                        // give up to avoid to continue to write data to a closed connection
                        _data = null;
                    }
                }
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

        public void setEndPoint(AsyncEndPoint endpoint)
        {
            _endPoint = endpoint;
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
                throw new IOException()
                {{
                        initCause(x);
                    }};
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
                LOG.debug(this + ": unexpected exception closing the client", x);
            }

            try
            {
                closeServer();
            }
            catch (IOException x)
            {
                LOG.debug(this + ": unexpected exception closing the server", x);
            }
        }

        public void shutdownOutput() throws IOException
        {
            writeData();
            _endPoint.shutdownOutput();
        }

        public void onIdleExpired(long idleForMs)
        {
            try
            {
                LOG.debug("{} idle expired", this);
                if (_endPoint.isOutputShutdown())
                    close();
                else
                    shutdownOutput();
            }
            catch(Exception e)
            {
                LOG.debug(e);
                close();
            }
        }
    }

    public class ClientToProxyConnection implements AsyncConnection
    {
        private final Buffer _buffer = new IndirectNIOBuffer(4096);
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
        }

        @Override
        public String toString()
        {
            StringBuilder builder = new StringBuilder("ClientToProxy");
            builder.append("(:").append(_endPoint.getLocalPort());
            builder.append("<=>:").append(_endPoint.getRemotePort());
            return builder.append(")").toString();
        }

        public Connection handle() throws IOException
        {
            LOG.debug("{}: begin reading from client", this);
            try
            {
                if (_firstTime)
                {
                    _firstTime = false;
                    register(_channel, _toServer);
                    LOG.debug("{}: registered channel {} with connection {}", this, _channel, _toServer);
                }

                while (true)
                {
                    int read = read(_endPoint, _buffer, _context);

                    if (read == -1)
                    {
                        LOG.debug("{}: client closed connection {}", this, _endPoint);

                        if (_endPoint.isOutputShutdown() || !_endPoint.isOpen())
                            closeServer();
                        else
                            _toServer.shutdownOutput();

                        break;
                    }

                    if (read == 0)
                        break;

                    LOG.debug("{}: read from client {} bytes {}", this, read, _endPoint);
                    int written = write(_toServer._endPoint, _buffer, _context);
                    LOG.debug("{}: written to {} {} bytes", this, _toServer, written);
                }
                return this;
            }
            catch (ClosedChannelException x)
            {
                LOG.debug(x);
                closeServer();
                throw x;
            }
            catch (IOException x)
            {
                LOG.warn(this + ": unexpected exception", x);
                close();
                throw x;
            }
            catch (RuntimeException x)
            {
                LOG.warn(this + ": unexpected exception", x);
                close();
                throw x;
            }
            finally
            {
                LOG.debug("{}: end reading from client", this);
            }
        }

        public void onInputShutdown() throws IOException
        {
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

        public void onClose()
        {
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
                LOG.debug(this + ": unexpected exception closing the client", x);
            }

            try
            {
                closeServer();
            }
            catch (IOException x)
            {
                LOG.debug(this + ": unexpected exception closing the server", x);
            }
        }

        public void shutdownOutput() throws IOException
        {
            _endPoint.shutdownOutput();
        }

        public void onIdleExpired(long idleForMs)
        {
            try
            {
                LOG.debug("{} idle expired", this);
                if (_endPoint.isOutputShutdown())
                    close();
                else
                    shutdownOutput();
            }
            catch(Exception e)
            {
                LOG.debug(e);
                close();
            }
        }
    }

    /**
     * Add a whitelist entry to an existing handler configuration
     *
     * @param entry new whitelist entry
     */
    public void addWhite(String entry)
    {
        add(entry, _white);
    }

    /**
     * Add a blacklist entry to an existing handler configuration
     *
     * @param entry new blacklist entry
     */
    public void addBlack(String entry)
    {
        add(entry, _black);
    }

    /**
     * Re-initialize the whitelist of existing handler object
     *
     * @param entries array of whitelist entries
     */
    public void setWhite(String[] entries)
    {
        set(entries, _white);
    }

    /**
     * Re-initialize the blacklist of existing handler object
     *
     * @param entries array of blacklist entries
     */
    public void setBlack(String[] entries)
    {
        set(entries, _black);
    }

    /**
     * Helper method to process a list of new entries and replace
     * the content of the specified host map
     *
     * @param entries new entries
     * @param hostMap target host map
     */
    protected void set(String[] entries, HostMap<String> hostMap)
    {
        hostMap.clear();

        if (entries != null && entries.length > 0)
        {
            for (String addrPath : entries)
            {
                add(addrPath, hostMap);
            }
        }
    }

    /**
     * Helper method to process the new entry and add it to
     * the specified host map.
     *
     * @param entry      new entry
     * @param hostMap target host map
     */
    private void add(String entry, HostMap<String> hostMap)
    {
        if (entry != null && entry.length() > 0)
        {
            entry = entry.trim();
            if (hostMap.get(entry) == null)
            {
                hostMap.put(entry, entry);
            }
        }
    }

    /**
     * Check the request hostname against white- and blacklist.
     *
     * @param host hostname to check
     * @return true if hostname is allowed to be proxied
     */
    public boolean validateDestination(String host)
    {
        if (_white.size() > 0)
        {
            Object whiteObj = _white.getLazyMatches(host);
            if (whiteObj == null)
            {
                return false;
            }
        }

        if (_black.size() > 0)
        {
            Object blackObj = _black.getLazyMatches(host);
            if (blackObj != null)
            {
                return false;
            }
        }

        return true;
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpThis(out);
        if (_privateThreadPool)
            dump(out, indent, Arrays.asList(_threadPool, _selectorManager), TypeUtil.asList(getHandlers()), getBeans());
        else
            dump(out, indent, Arrays.asList(_selectorManager), TypeUtil.asList(getHandlers()), getBeans());
    }
}
