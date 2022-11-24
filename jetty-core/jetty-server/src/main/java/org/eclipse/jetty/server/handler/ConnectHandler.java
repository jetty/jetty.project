//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.TunnelSupport;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Implementation of a {@link Handler} that supports HTTP CONNECT.</p>
 */
public class ConnectHandler extends Handler.Wrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(ConnectHandler.class);

    private final Set<String> whiteList = new HashSet<>();
    private final Set<String> blackList = new HashSet<>();
    private Executor executor;
    private Scheduler scheduler;
    private ByteBufferPool bufferPool;
    private SelectorManager selector;
    private long connectTimeout = 15000;
    private long idleTimeout = 30000;
    private int bufferSize = 4096;

    public ConnectHandler()
    {
        this(null);
    }

    public ConnectHandler(Handler handler)
    {
        setHandler(handler);
    }

    public Executor getExecutor()
    {
        return executor;
    }

    public void setExecutor(Executor executor)
    {
        this.executor = executor;
    }

    public Scheduler getScheduler()
    {
        return scheduler;
    }

    public void setScheduler(Scheduler scheduler)
    {
        updateBean(this.scheduler, scheduler);
        this.scheduler = scheduler;
    }

    public ByteBufferPool getByteBufferPool()
    {
        return bufferPool;
    }

    public void setByteBufferPool(ByteBufferPool bufferPool)
    {
        updateBean(this.bufferPool, bufferPool);
        this.bufferPool = bufferPool;
    }

    /**
     * @return the timeout, in milliseconds, to connect to the remote server
     */
    public long getConnectTimeout()
    {
        return connectTimeout;
    }

    /**
     * @param connectTimeout the timeout, in milliseconds, to connect to the remote server
     */
    public void setConnectTimeout(long connectTimeout)
    {
        this.connectTimeout = connectTimeout;
    }

    /**
     * @return the idle timeout, in milliseconds
     */
    public long getIdleTimeout()
    {
        return idleTimeout;
    }

    /**
     * @param idleTimeout the idle timeout, in milliseconds
     */
    public void setIdleTimeout(long idleTimeout)
    {
        this.idleTimeout = idleTimeout;
    }

    public int getBufferSize()
    {
        return bufferSize;
    }

    public void setBufferSize(int bufferSize)
    {
        this.bufferSize = bufferSize;
    }

    @Override
    protected void doStart() throws Exception
    {
        if (executor == null)
            executor = getServer().getThreadPool();

        if (scheduler == null)
        {
            scheduler = getServer().getBean(Scheduler.class);
            if (scheduler == null)
                scheduler = new ScheduledExecutorScheduler(String.format("Proxy-Scheduler-%x", hashCode()), false);
            addBean(scheduler);
        }

        if (bufferPool == null)
        {
            bufferPool = new MappedByteBufferPool();
            addBean(bufferPool);
        }

        addBean(selector = newSelectorManager());
        selector.setConnectTimeout(getConnectTimeout());

        super.doStart();
    }

    protected SelectorManager newSelectorManager()
    {
        return new ConnectManager(getExecutor(), getScheduler(), 1);
    }

    @Override
    public void process(Request request, Response response, Callback callback) throws Exception
    {
        if (HttpMethod.CONNECT.is(request.getMethod()))
        {
            TunnelSupport tunnelSupport = request.getTunnelSupport();
            if (tunnelSupport != null)
            {
                if (tunnelSupport.getProtocol() == null)
                {
                    return (req, res, cbk) ->
                    {
                        HttpURI httpURI = req.getHttpURI();
                        String serverAddress = httpURI.getAuthority();
                        if (LOG.isDebugEnabled())
                            LOG.debug("CONNECT request for {}", serverAddress);
                        handleConnect(req, res, cbk, serverAddress);
                    };
                }
            }
            else
            {
                return (req, res, cbk) -> Response.writeError(req, res, cbk, HttpStatus.NOT_IMPLEMENTED_501);
            }
        }
        return super.process(request, response, callback);
    }

    /**
     * <p>Handles a CONNECT request.</p>
     * <p>CONNECT requests may have authentication headers such as {@code Proxy-Authorization}
     * that authenticate the client with the proxy.</p>
     *
     * @param request the jetty request
     * @param response the jetty response
     * @param callback the callback with which to generate a response
     * @param serverAddress the remote server address in the form {@code host:port}
     */
    protected void handleConnect(Request request, Response response, Callback callback, String serverAddress)
    {
        try
        {
            boolean proceed = handleAuthentication(request, response, serverAddress);
            if (!proceed)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Missing proxy authentication");
                sendConnectResponse(request, response, callback, HttpStatus.PROXY_AUTHENTICATION_REQUIRED_407);
                return;
            }
        
            HostPort hostPort = new HostPort(serverAddress);
            String host = hostPort.getHost();
            int port = hostPort.getPort(80);
        
            if (!validateDestination(host, port))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Destination {}:{} forbidden", host, port);
                sendConnectResponse(request, response, callback, HttpStatus.FORBIDDEN_403);
                return;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Connecting to {}:{}", host, port);
        
            connectToServer(request, host, port, new Promise<>()
            {
                @Override
                public void succeeded(SocketChannel channel)
                {
                    ConnectContext connectContext = new ConnectContext(request, response, callback, request.getTunnelSupport().getEndPoint());
                    if (channel.isConnected())
                        selector.accept(channel, connectContext);
                    else
                        selector.connect(channel, connectContext);
                }
        
                @Override
                public void failed(Throwable x)
                {
                    onConnectFailure(request, response, callback, x);
                }
            });
        }
        catch (Exception x)
        {
            onConnectFailure(request, response, callback, x);
        }
    }

    protected void connectToServer(Request request, String host, int port, Promise<SocketChannel> promise)
    {
        SocketChannel channel = null;
        try
        {
            channel = SocketChannel.open();
            channel.socket().setTcpNoDelay(true);
            channel.configureBlocking(false);
            InetSocketAddress address = newConnectAddress(host, port);
            channel.connect(address);
            promise.succeeded(channel);
        }
        catch (Throwable x)
        {
            close(channel);
            promise.failed(x);
        }
    }

    private void close(Closeable closeable)
    {
        try
        {
            if (closeable != null)
                closeable.close();
        }
        catch (Throwable x)
        {
            LOG.trace("IGNORED", x);
        }
    }

    /**
     * Creates the server address to connect to.
     *
     * @param host The host from the CONNECT request
     * @param port The port from the CONNECT request
     * @return The InetSocketAddress to connect to.
     */
    protected InetSocketAddress newConnectAddress(String host, int port)
    {
        return new InetSocketAddress(host, port);
    }

    protected void onConnectSuccess(ConnectContext connectContext, UpstreamConnection upstreamConnection)
    {
        ConcurrentMap<String, Object> context = connectContext.getContext();
        Request request = connectContext.getRequest();
        prepareContext(request, context);

        EndPoint downstreamEndPoint = connectContext.getEndPoint();
        DownstreamConnection downstreamConnection = newDownstreamConnection(downstreamEndPoint, context);
        downstreamConnection.setInputBufferSize(getBufferSize());

        upstreamConnection.setConnection(downstreamConnection);
        downstreamConnection.setConnection(upstreamConnection);
        if (LOG.isDebugEnabled())
            LOG.debug("Connection setup completed: {}<->{}", downstreamConnection, upstreamConnection);

        Response response = connectContext.getResponse();
        upgradeConnection(request, downstreamConnection);
        sendConnectResponse(request, response, connectContext.callback, HttpStatus.OK_200);
    }

    protected void onConnectFailure(Request request, Response response, Callback callback, Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("CONNECT failed", failure);
        sendConnectResponse(request, response, callback, HttpStatus.INTERNAL_SERVER_ERROR_500);
    }

    private void sendConnectResponse(Request request, Response response, Callback callback, int statusCode)
    {
        try
        {
            response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, 0);
            if (statusCode != HttpStatus.OK_200)
            {
                response.getHeaders().put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE);
                Response.writeError(request, response, callback, statusCode);
            }
            else
            {
                response.setStatus(HttpStatus.OK_200);
                callback.succeeded();
            }
            if (LOG.isDebugEnabled())
                LOG.debug("CONNECT response sent {} {}", request.getConnectionMetaData().getProtocol(), statusCode);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Could not send CONNECT response", x);
        }
    }

    /**
     * <p>Handles the authentication before setting up the tunnel to the remote server.</p>
     * <p>The default implementation returns true.</p>
     *
     * @param request the HTTP request
     * @param response the HTTP response
     * @param address the address of the remote server in the form {@code host:port}.
     * @return true to allow to connect to the remote host, false otherwise
     */
    protected boolean handleAuthentication(Request request, Response response, String address)
    {
        return true;
    }

    protected DownstreamConnection newDownstreamConnection(EndPoint endPoint, ConcurrentMap<String, Object> context)
    {
        return new DownstreamConnection(endPoint, getExecutor(), getByteBufferPool(), context);
    }

    protected UpstreamConnection newUpstreamConnection(EndPoint endPoint, ConnectContext connectContext)
    {
        return new UpstreamConnection(endPoint, getExecutor(), getByteBufferPool(), connectContext);
    }

    protected void prepareContext(Request request, ConcurrentMap<String, Object> context)
    {
    }

    private void upgradeConnection(Request request, Connection connection)
    {
        // Set the new connection as request attribute so that
        // Jetty understands that it has to upgrade the connection.
        request.setAttribute(HttpStream.UPGRADE_CONNECTION_ATTRIBUTE, connection);
        if (LOG.isDebugEnabled())
            LOG.debug("Upgraded connection to {}", connection);
    }

    /**
     * <p>Reads (with non-blocking semantic) into the given {@code buffer} from the given {@code endPoint}.</p>
     *
     * @param endPoint the endPoint to read from
     * @param buffer the buffer to read data into
     * @param context the context information related to the connection
     * @return the number of bytes read (possibly 0 since the read is non-blocking)
     * or -1 if the channel has been closed remotely
     * @throws IOException if the endPoint cannot be read
     */
    protected int read(EndPoint endPoint, ByteBuffer buffer, ConcurrentMap<String, Object> context) throws IOException
    {
        return endPoint.fill(buffer);
    }

    /**
     * <p>Writes (with non-blocking semantic) the given buffer of data onto the given endPoint.</p>
     *
     * @param endPoint the endPoint to write to
     * @param buffer the buffer to write
     * @param callback the completion callback to invoke
     * @param context the context information related to the connection
     */
    protected void write(EndPoint endPoint, ByteBuffer buffer, Callback callback, ConcurrentMap<String, Object> context)
    {
        endPoint.write(callback, buffer);
    }

    public Set<String> getWhiteListHosts()
    {
        return whiteList;
    }

    public Set<String> getBlackListHosts()
    {
        return blackList;
    }

    /**
     * Checks the given {@code host} and {@code port} against whitelist and blacklist.
     *
     * @param host the host to check
     * @param port the port to check
     * @return true if it is allowed to connect to the given host and port
     */
    public boolean validateDestination(String host, int port)
    {
        String hostPort = host + ":" + port;
        if (!whiteList.isEmpty())
        {
            if (!whiteList.contains(hostPort))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Host {}:{} not whitelisted", host, port);
                return false;
            }
        }
        if (!blackList.isEmpty())
        {
            if (blackList.contains(hostPort))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Host {}:{} blacklisted", host, port);
                return false;
            }
        }
        return true;
    }

    protected class ConnectManager extends SelectorManager
    {
        protected ConnectManager(Executor executor, Scheduler scheduler, int selectors)
        {
            super(executor, scheduler, selectors);
        }

        @Override
        protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey key)
        {
            SocketChannelEndPoint endPoint = new SocketChannelEndPoint((SocketChannel)channel, selector, key, getScheduler());
            endPoint.setIdleTimeout(getIdleTimeout());
            return endPoint;
        }

        @Override
        public Connection newConnection(SelectableChannel channel, EndPoint endpoint, Object attachment) throws IOException
        {
            if (ConnectHandler.LOG.isDebugEnabled())
                ConnectHandler.LOG.debug("Connected to {}", ((SocketChannel)channel).getRemoteAddress());
            ConnectContext connectContext = (ConnectContext)attachment;
            UpstreamConnection connection = newUpstreamConnection(endpoint, connectContext);
            connection.setInputBufferSize(getBufferSize());
            return connection;
        }

        @Override
        protected void connectionFailed(SelectableChannel channel, final Throwable ex, final Object attachment)
        {
            close(channel);
            ConnectContext connectContext = (ConnectContext)attachment;
            onConnectFailure(connectContext.request, connectContext.response, connectContext.callback, ex);
        }
    }

    protected static class ConnectContext
    {
        private final ConcurrentMap<String, Object> context = new ConcurrentHashMap<>();
        private final Request request;
        private final Response response;
        private final Callback callback;
        private final EndPoint endPoint;

        public ConnectContext(Request request, Response response, Callback callback, EndPoint endPoint)
        {
            this.request = request;
            this.response = response;
            this.callback = callback;
            this.endPoint = endPoint;
        }

        public ConcurrentMap<String, Object> getContext()
        {
            return context;
        }

        public Request getRequest()
        {
            return request;
        }

        public Response getResponse()
        {
            return response;
        }

        public Callback getCallback()
        {
            return callback;
        }

        public EndPoint getEndPoint()
        {
            return endPoint;
        }
    }

    public class UpstreamConnection extends TunnelConnection
    {
        private final ConnectContext connectContext;

        public UpstreamConnection(EndPoint endPoint, Executor executor, ByteBufferPool bufferPool, ConnectContext connectContext)
        {
            super(endPoint, executor, bufferPool, connectContext.getContext());
            this.connectContext = connectContext;
        }

        @Override
        public void onOpen()
        {
            super.onOpen();
            onConnectSuccess(connectContext, this);
            fillInterested();
        }

        @Override
        protected int read(EndPoint endPoint, ByteBuffer buffer) throws IOException
        {
            int read = ConnectHandler.this.read(endPoint, buffer, getContext());
            if (LOG.isDebugEnabled())
                LOG.debug("Read {} bytes from server {}", read, this);
            return read;
        }

        @Override
        protected void write(EndPoint endPoint, ByteBuffer buffer, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Writing {} bytes to client {}", buffer.remaining(), this);
            ConnectHandler.this.write(endPoint, buffer, callback, getContext());
        }
    }

    public class DownstreamConnection extends TunnelConnection implements Connection.UpgradeTo
    {
        private ByteBuffer buffer;

        public DownstreamConnection(EndPoint endPoint, Executor executor, ByteBufferPool bufferPool, ConcurrentMap<String, Object> context)
        {
            super(endPoint, executor, bufferPool, context);
        }

        @Override
        public void onUpgradeTo(ByteBuffer buffer)
        {
            this.buffer = buffer;
        }

        @Override
        public void onOpen()
        {
            super.onOpen();

            if (buffer == null)
            {
                fillInterested();
                return;
            }

            int remaining = buffer.remaining();
            write(getConnection().getEndPoint(), buffer, new Callback()
            {
                @Override
                public void succeeded()
                {
                    buffer = null;
                    if (LOG.isDebugEnabled())
                        LOG.debug("Wrote initial {} bytes to server {}", remaining, DownstreamConnection.this);
                    fillInterested();
                }

                @Override
                public void failed(Throwable x)
                {
                    buffer = null;
                    if (LOG.isDebugEnabled())
                        LOG.debug("Failed to write initial {} bytes to server {}", remaining, DownstreamConnection.this, x);
                    close();
                    getConnection().close();
                }
            });
        }

        @Override
        protected int read(EndPoint endPoint, ByteBuffer buffer) throws IOException
        {
            int read = ConnectHandler.this.read(endPoint, buffer, getContext());
            if (LOG.isDebugEnabled())
                LOG.debug("Read {} bytes from client {}", read, this);
            return read;
        }

        @Override
        protected void write(EndPoint endPoint, ByteBuffer buffer, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Writing {} bytes to server {}", buffer.remaining(), this);
            ConnectHandler.this.write(endPoint, buffer, callback, getContext());
        }
    }

    private abstract static class TunnelConnection extends AbstractConnection
    {
        private final IteratingCallback pipe = new ProxyIteratingCallback();
        private final ByteBufferPool bufferPool;
        private final ConcurrentMap<String, Object> context;
        private TunnelConnection connection;

        protected TunnelConnection(EndPoint endPoint, Executor executor, ByteBufferPool bufferPool, ConcurrentMap<String, Object> context)
        {
            super(endPoint, executor);
            this.bufferPool = bufferPool;
            this.context = context;
        }

        public ByteBufferPool getByteBufferPool()
        {
            return bufferPool;
        }

        public ConcurrentMap<String, Object> getContext()
        {
            return context;
        }

        public Connection getConnection()
        {
            return connection;
        }

        public void setConnection(TunnelConnection connection)
        {
            this.connection = connection;
        }

        @Override
        public void onFillable()
        {
            pipe.iterate();
        }

        protected abstract int read(EndPoint endPoint, ByteBuffer buffer) throws IOException;

        protected abstract void write(EndPoint endPoint, ByteBuffer buffer, Callback callback);

        protected void close(Throwable failure)
        {
            getEndPoint().close(failure);
        }

        @Override
        public String toConnectionString()
        {
            EndPoint endPoint = getEndPoint();
            return String.format("%s@%x[l:%s<=>r:%s]",
                getClass().getSimpleName(),
                hashCode(),
                endPoint.getLocalSocketAddress(),
                endPoint.getRemoteSocketAddress());
        }

        private class ProxyIteratingCallback extends IteratingCallback
        {
            private ByteBuffer buffer;
            private int filled;

            @Override
            protected Action process()
            {
                buffer = bufferPool.acquire(getInputBufferSize(), true);
                try
                {
                    int filled = this.filled = read(getEndPoint(), buffer);
                    if (filled > 0)
                    {
                        write(connection.getEndPoint(), buffer, this);
                        return Action.SCHEDULED;
                    }
                    else if (filled == 0)
                    {
                        bufferPool.release(buffer);
                        fillInterested();
                        return Action.IDLE;
                    }
                    else
                    {
                        bufferPool.release(buffer);
                        connection.getEndPoint().shutdownOutput();
                        return Action.SUCCEEDED;
                    }
                }
                catch (IOException x)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Could not fill {}", TunnelConnection.this, x);
                    bufferPool.release(buffer);
                    disconnect(x);
                    return Action.SUCCEEDED;
                }
            }

            @Override
            public void succeeded()
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Wrote {} bytes {}", filled, TunnelConnection.this);
                bufferPool.release(buffer);
                super.succeeded();
            }

            @Override
            protected void onCompleteSuccess()
            {
            }

            @Override
            protected void onCompleteFailure(Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Failed to write {} bytes {}", filled, TunnelConnection.this, x);
                bufferPool.release(buffer);
                disconnect(x);
            }

            private void disconnect(Throwable x)
            {
                TunnelConnection.this.close(x);
                connection.close(x);
            }
        }
    }
}
