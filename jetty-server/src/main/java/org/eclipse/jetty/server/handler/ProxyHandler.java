package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.IndirectNIOBuffer;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.io.nio.SelectorManager;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/**
 * @version $Revision$ $Date$
 */
public class ProxyHandler extends AbstractHandler
{
    private final Logger logger = Log.getLogger(getClass().getName());
    private final SelectorManager selectorManager = new Manager();
    private final String serverAddress;
    private volatile int connectTimeout = 5000;
    private volatile int writeTimeout = 30000;
    private volatile QueuedThreadPool threadPool;

    public ProxyHandler()
    {
        this(null);
    }

    public ProxyHandler(String serverAddress)
    {
        this.serverAddress = serverAddress;
    }

    public int getConnectTimeout()
    {
        return connectTimeout;
    }

    public void setConnectTimeout(int connectTimeout)
    {
        this.connectTimeout = connectTimeout;
    }

    public int getWriteTimeout()
    {
        return writeTimeout;
    }

    public void setWriteTimeout(int writeTimeout)
    {
        this.writeTimeout = writeTimeout;
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        // TODO: configure threadPool
        threadPool = new QueuedThreadPool();
        threadPool.start();

        selectorManager.start();
        threadPool.dispatch(new Runnable()
        {
            public void run()
            {
                while (isRunning())
                {
                    try
                    {
                        selectorManager.doSelect(0);
                    }
                    catch (IOException x)
                    {
                        logger.warn("Unexpected exception", x);
                    }
                }
            }
        });
    }

    @Override
    protected void doStop() throws Exception
    {
        selectorManager.stop();

        QueuedThreadPool threadPool = this.threadPool;
        if (threadPool != null)
            threadPool.stop();

        super.doStop();
    }

    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        if (HttpMethods.CONNECT.equalsIgnoreCase(request.getMethod()))
        {
            logger.info("CONNECT request for {}", request.getRequestURI(), null);
            handle(request, response, request.getRequestURI());
        }
        else
        {
            logger.info("Plain request for {}", serverAddress, null);
            if (serverAddress == null)
                throw new ServletException("Parameter 'serverAddress' cannot be null");
            handle(request, response, serverAddress);
        }
    }

    /**
     * <p>Handles a CONNECT request.</p>
     * <p>CONNECT requests may have authentication headers such as <code>Proxy-Authorization</code>
     * that authenticate the client with the proxy.</p>
     * @param request the http request
     * @param response the http response
     * @param connectURI the CONNECT URI
     * @throws ServletException if an application error occurs
     * @throws IOException if an I/O error occurs
     */
    protected void handle(HttpServletRequest request, HttpServletResponse response, String connectURI) throws ServletException, IOException
    {
        boolean proceed = handleAuthentication(request, response, connectURI);
        if (!proceed)
            return;

        String host = connectURI;
        int port = 80;
        boolean secure = false;
        int colon = connectURI.indexOf(':');
        if (colon > 0)
        {
            host = connectURI.substring(0, colon);
            port = Integer.parseInt(connectURI.substring(colon + 1));
            secure = isTunnelSecure(host, port);
        }

        setupTunnel(request, response, host, port, secure);
    }

    protected boolean isTunnelSecure(String host, int port)
    {
        return port == 443;
    }

    protected boolean handleAuthentication(HttpServletRequest request, HttpServletResponse response, String connectURI) throws ServletException, IOException
    {
        return true;
    }

    protected void setupTunnel(HttpServletRequest request, HttpServletResponse response, String host, int port, boolean secure) throws IOException
    {
        SocketChannel channel = connect(request, host, port);
        channel.configureBlocking(false);

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

        // Setup connections, before registering the channel to avoid races
        // where the server sends data before the connections are set up
        ProxyToServerConnection proxyToServer = new ProxyToServerConnection(secure, buffer);
        ClientToProxyConnection clientToProxy = new ClientToProxyConnection(channel, httpConnection.getEndPoint(), httpConnection.getTimeStamp());
        clientToProxy.setConnection(proxyToServer);
        proxyToServer.setConnection(clientToProxy);

        upgradeConnection(request, response, clientToProxy);
    }

    protected SocketChannel connect(HttpServletRequest request, String host, int port) throws IOException
    {
        logger.info("Establishing connection to {}:{}", host, port);
        // Connect to remote server
        SocketChannel channel = SocketChannel.open();
        channel.socket().setTcpNoDelay(true);
        channel.socket().connect(new InetSocketAddress(host, port), getConnectTimeout());
        logger.info("Established connection to {}:{}", host, port);
        return channel;
    }

    private void upgradeConnection(HttpServletRequest request, HttpServletResponse response, Connection connection) throws IOException
    {
        // CONNECT expects a 200 response
        response.setStatus(HttpServletResponse.SC_OK);
        // Flush it so that the client receives it
        response.flushBuffer();
        // Set the new connection as request attribute and change the status to 101
        // so that Jetty understands that it has to upgrade the connection
        request.setAttribute("org.eclipse.jetty.io.Connection", connection);
        response.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
        logger.info("Upgraded connection to {}", connection, null);
    }

    private void register(SocketChannel channel, ProxyToServerConnection proxyToServer) throws IOException
    {
        selectorManager.register(channel, proxyToServer);
        proxyToServer.waitReady(connectTimeout);
    }

    /**
     * Writes (with blocking semantic) the given buffer of data onto the given endPoint
     * @param endPoint the endPoint to write to
     * @param buffer the buffer to write
     * @throws IOException if the buffer cannot be written
     */
    private void write(EndPoint endPoint, Buffer buffer) throws IOException
    {
        if (buffer == null)
            return;

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
        logger.info("Written {}/{} bytes " + endPoint, builder, length);
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
            ProxyToServerConnection proxyToServer = (ProxyToServerConnection)selectionKey.attachment();
            if (proxyToServer.secure)
            {
                throw new UnsupportedOperationException();
//                return new SslSelectChannelEndPoint(???, channel, selectSet, selectionKey, sslContext.createSSLEngine(address.host, address.port));
            }
            else
            {
                return new SelectChannelEndPoint(channel, selectSet, selectionKey);
            }
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
            return threadPool.dispatch(task);
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

    private class ProxyToServerConnection implements Connection
    {
        private final CountDownLatch ready = new CountDownLatch(1);
        private final Buffer buffer = new IndirectNIOBuffer(1024);
        private final boolean secure;
        private volatile Buffer data;
        private volatile ClientToProxyConnection connection;
        private volatile long timestamp;
        private volatile SelectChannelEndPoint endPoint;

        public ProxyToServerConnection(boolean secure, Buffer data)
        {
            this.secure = secure;
            this.data = data;
        }

        public Connection handle() throws IOException
        {
            logger.info("ProxyToServer: handle entered");
            if (data != null)
            {
                write(endPoint, data);
                data = null;
            }

            while (true)
            {
                int read = endPoint.fill(buffer);

                if (read == -1)
                {
                    logger.info("ProxyToServer: closed connection {}", endPoint, null);
                    connection.close();
                    break;
                }

                if (read == 0)
                    break;

                logger.info("ProxyToServer: read {} bytes {}", read, endPoint);
                write(connection.endPoint, buffer);
            }
            logger.info("ProxyToServer: handle exited");
            return this;
        }

        public void setConnection(ClientToProxyConnection connection)
        {
            this.connection = connection;
        }

        public long getTimeStamp()
        {
            return timestamp;
        }

        public void setTimeStamp(long timestamp)
        {
            this.timestamp = timestamp;
        }

        public void setEndPoint(SelectChannelEndPoint endpoint)
        {
            this.endPoint = endpoint;
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
            ready.countDown();
        }

        public void waitReady(long timeout) throws IOException
        {
            try
            {
                ready.await(timeout, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException x)
            {
                throw new IOException(x);
            }
        }

        public void close() throws IOException
        {
            endPoint.close();
        }
    }

    private class ClientToProxyConnection implements Connection
    {
        private final Buffer buffer = new IndirectNIOBuffer(1024);
        private final SocketChannel channel;
        private final EndPoint endPoint;
        private final long timestamp;
        private volatile ProxyToServerConnection connection;
        private boolean firstTime = true;

        public ClientToProxyConnection(SocketChannel channel, EndPoint endPoint, long timestamp)
        {
            this.channel = channel;
            this.endPoint = endPoint;
            this.timestamp = timestamp;
        }

        public Connection handle() throws IOException
        {
            logger.info("ClientToProxy: handle entered");

            if (firstTime)
            {
                firstTime = false;
                register(channel, connection);
            }

            while (true)
            {
                int read = endPoint.fill(buffer);

                if (read == -1)
                {
                    logger.info("ClientToProxy: closed connection {}", endPoint, null);
                    connection.close();
                    break;
                }

                if (read == 0)
                    break;

                logger.info("ClientToProxy: read {} bytes {}", read, endPoint);
                write(connection.endPoint, buffer);
            }
            logger.info("ClientToProxy: handle exited");
            return this;
        }

        public long getTimeStamp()
        {
            return timestamp;
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
            this.connection = connection;
        }

        public void close() throws IOException
        {
            endPoint.close();
        }
    }
}
