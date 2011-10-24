// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.client;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.UnresolvedAddressException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.ssl.SslContextFactory;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.Buffers.Type;
import org.eclipse.jetty.io.BuffersFactory;
import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.io.nio.SelectorManager;
import org.eclipse.jetty.io.nio.SslSelectChannelEndPoint;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Timeout;

class SelectConnector extends AbstractLifeCycle implements HttpClient.Connector
{
    private static final Logger LOG = Log.getLogger(SelectConnector.class);

    private final HttpClient _httpClient;
    private final Manager _selectorManager=new Manager();
    private final Map<SocketChannel, Timeout.Task> _connectingChannels = new ConcurrentHashMap<SocketChannel, Timeout.Task>();
    private Buffers _sslBuffers;

    /**
     * @param httpClient the HttpClient this connector is associated to
     */
    SelectConnector(HttpClient httpClient)
    {
        _httpClient = httpClient;
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStart() throws Exception
    {
        super.doStart();


        final boolean direct=_httpClient.getUseDirectBuffers();

        SSLEngine sslEngine=_selectorManager.newSslEngine(null);
        final SSLSession ssl_session=sslEngine.getSession();
        _sslBuffers = BuffersFactory.newBuffers(
                direct?Type.DIRECT:Type.INDIRECT,ssl_session.getApplicationBufferSize(),
                direct?Type.DIRECT:Type.INDIRECT,ssl_session.getApplicationBufferSize(),
                direct?Type.DIRECT:Type.INDIRECT,1024);

        _selectorManager.start();
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStop() throws Exception
    {
        _selectorManager.stop();
    }

    /* ------------------------------------------------------------ */
    public void startConnection( HttpDestination destination )
        throws IOException
    {
        SocketChannel channel = null;
        try
        {
            channel = SocketChannel.open();
            Address address = destination.isProxied() ? destination.getProxy() : destination.getAddress();
            channel.socket().setTcpNoDelay(true);

            if (_httpClient.isConnectBlocking())
            {
                    channel.socket().connect(address.toSocketAddress(), _httpClient.getConnectTimeout());
                    channel.configureBlocking(false);
                    _selectorManager.register( channel, destination );
            }
            else
            {
                channel.configureBlocking(false);
                channel.connect(address.toSocketAddress());            
                _selectorManager.register(channel,destination);
                ConnectTimeout connectTimeout = new ConnectTimeout(channel,destination);
                _httpClient.schedule(connectTimeout,_httpClient.getConnectTimeout());
                _connectingChannels.put(channel,connectTimeout);
            }
        }
        catch (UnresolvedAddressException ex)
        {
            if (channel != null)
                channel.close();
            destination.onConnectionFailed(ex);
        }
        catch(IOException ex)
        {
            if (channel != null)
                channel.close();
            destination.onConnectionFailed(ex);
        }
    }

    /* ------------------------------------------------------------ */
    class Manager extends SelectorManager
    {
        @Override
        public boolean dispatch(Runnable task)
        {
            return _httpClient._threadPool.dispatch(task);
        }

        @Override
        protected void endPointOpened(SelectChannelEndPoint endpoint)
        {
        }

        @Override
        protected void endPointClosed(SelectChannelEndPoint endpoint)
        {
        }

        @Override
        protected void endPointUpgraded(ConnectedEndPoint endpoint, Connection oldConnection)
        {
        }

        @Override
        protected Connection newConnection(SocketChannel channel, SelectChannelEndPoint endpoint)
        {
            if (endpoint instanceof SslSelectChannelEndPoint)
                return new HttpConnection(_sslBuffers,_sslBuffers,endpoint);

            return new HttpConnection(_httpClient.getRequestBuffers(),_httpClient.getResponseBuffers(),endpoint);
        }

        @Override
        protected SelectChannelEndPoint newEndPoint(SocketChannel channel, SelectSet selectSet, SelectionKey key) throws IOException
        {
            // We're connected, cancel the connect timeout
            Timeout.Task connectTimeout = _connectingChannels.remove(channel);
            if (connectTimeout != null)
                connectTimeout.cancel();
            if (LOG.isDebugEnabled())
                LOG.debug("Channels with connection pending: {}", _connectingChannels.size());

            // key should have destination at this point (will be replaced by endpoint after this call)
            HttpDestination dest=(HttpDestination)key.attachment();

            SelectChannelEndPoint ep=null;

            if (dest.isSecure())
            {
                if (dest.isProxied())
                {
                    SSLEngine engine=newSslEngine(channel);
                    ep = new ProxySelectChannelEndPoint(channel, selectSet, key, _sslBuffers, engine, (int)_httpClient.getIdleTimeout());
                }
                else
                {
                    SSLEngine engine=newSslEngine(channel);
                    SslSelectChannelEndPoint sslEp = new SslSelectChannelEndPoint(_sslBuffers, channel, selectSet, key, engine, (int)_httpClient.getIdleTimeout());
                    sslEp.setAllowRenegotiate(_httpClient.getSslContextFactory().isAllowRenegotiate());
                    ep = sslEp;
                }
            }
            else
            {
                ep = new SelectChannelEndPoint(channel, selectSet, key, (int)_httpClient.getIdleTimeout());
            }

            HttpConnection connection=(HttpConnection)ep.getConnection();
            connection.setDestination(dest);
            dest.onNewConnection(connection);
            return ep;
        }

        private synchronized SSLEngine newSslEngine(SocketChannel channel) throws IOException
        {
            SslContextFactory sslContextFactory = _httpClient.getSslContextFactory();
            SSLEngine sslEngine;
            if (channel != null)
            {
                String peerHost = channel.socket().getInetAddress().getHostAddress();
                int peerPort = channel.socket().getPort();
                sslEngine = sslContextFactory.newSslEngine(peerHost, peerPort);
            }
            else
            {
                sslEngine = sslContextFactory.newSslEngine();
            }
            sslEngine.setUseClientMode(true);
            sslEngine.beginHandshake();

            return sslEngine;
        }

        /* ------------------------------------------------------------ */
        /* (non-Javadoc)
         * @see org.eclipse.io.nio.SelectorManager#connectionFailed(java.nio.channels.SocketChannel, java.lang.Throwable, java.lang.Object)
         */
        @Override
        protected void connectionFailed(SocketChannel channel, Throwable ex, Object attachment)
        {
            Timeout.Task connectTimeout = _connectingChannels.remove(channel);
            if (connectTimeout != null)
                connectTimeout.cancel();
            
            if (attachment instanceof HttpDestination)
                ((HttpDestination)attachment).onConnectionFailed(ex);
            else
                super.connectionFailed(channel,ex,attachment);
        }
    }

    private class ConnectTimeout extends Timeout.Task
    {
        private final SocketChannel channel;
        private final HttpDestination destination;

        public ConnectTimeout(SocketChannel channel, HttpDestination destination)
        {
            this.channel = channel;
            this.destination = destination;
        }

        @Override
        public void expired()
        {
            if (channel.isConnectionPending())
            {
                LOG.debug("Channel {} timed out while connecting, closing it", channel);
                try
                {
                    // This will unregister the channel from the selector
                    channel.close();
                }
                catch (IOException x)
                {
                    LOG.ignore(x);
                }
                destination.onConnectionFailed(new SocketTimeoutException());
            }
        }
    }

    /**
     * An endpoint that is able to "upgrade" from a normal endpoint to a SSL endpoint.
     * Since {@link HttpParser} and {@link HttpGenerator} only depend on the {@link EndPoint}
     * interface, this class overrides all methods of {@link EndPoint} to provide the right
     * behavior depending on the fact that it has been upgraded or not.
     */
    public static class ProxySelectChannelEndPoint extends SslSelectChannelEndPoint
    {
        private final SelectChannelEndPoint plainEndPoint;
        private volatile boolean upgraded = false;

        public ProxySelectChannelEndPoint(SocketChannel channel, SelectorManager.SelectSet selectSet, SelectionKey key, Buffers sslBuffers, SSLEngine engine, int maxIdleTimeout) throws IOException
        {
            super(sslBuffers, channel, selectSet, key, engine, maxIdleTimeout);
            this.plainEndPoint = new SelectChannelEndPoint(channel, selectSet, key, maxIdleTimeout);
        }

        public void upgrade()
        {
            upgraded = true;
        }

        public void shutdownOutput() throws IOException
        {
            if (upgraded)
                super.shutdownOutput();
            else
                plainEndPoint.shutdownOutput();
        }

        public void close() throws IOException
        {
            if (upgraded)
                super.close();
            else
                plainEndPoint.close();
        }

        public int fill(Buffer buffer) throws IOException
        {
            if (upgraded)
                return super.fill(buffer);
            else
                return plainEndPoint.fill(buffer);
        }

        public int flush(Buffer buffer) throws IOException
        {
            if (upgraded)
                return super.flush(buffer);
            else
                return plainEndPoint.flush(buffer);
        }

        public int flush(Buffer header, Buffer buffer, Buffer trailer) throws IOException
        {
            if (upgraded)
                return super.flush(header, buffer, trailer);
            else
                return plainEndPoint.flush(header, buffer, trailer);
        }

        public String getLocalAddr()
        {
            if (upgraded)
                return super.getLocalAddr();
            else
                return plainEndPoint.getLocalAddr();
        }

        public String getLocalHost()
        {
            if (upgraded)
                return super.getLocalHost();
            else
                return plainEndPoint.getLocalHost();
        }

        public int getLocalPort()
        {
            if (upgraded)
                return super.getLocalPort();
            else
                return plainEndPoint.getLocalPort();
        }

        public String getRemoteAddr()
        {
            if (upgraded)
                return super.getRemoteAddr();
            else
                return plainEndPoint.getRemoteAddr();
        }

        public String getRemoteHost()
        {
            if (upgraded)
                return super.getRemoteHost();
            else
                return plainEndPoint.getRemoteHost();
        }

        public int getRemotePort()
        {
            if (upgraded)
                return super.getRemotePort();
            else
                return plainEndPoint.getRemotePort();
        }

        public boolean isBlocking()
        {
            if (upgraded)
                return super.isBlocking();
            else
                return plainEndPoint.isBlocking();
        }

        public boolean isBufferred()
        {
            if (upgraded)
                return super.isBufferred();
            else
                return plainEndPoint.isBufferred();
        }

        public boolean blockReadable(long millisecs) throws IOException
        {
            if (upgraded)
                return super.blockReadable(millisecs);
            else
                return plainEndPoint.blockReadable(millisecs);
        }

        public boolean blockWritable(long millisecs) throws IOException
        {
            if (upgraded)
                return super.blockWritable(millisecs);
            else
                return plainEndPoint.blockWritable(millisecs);
        }

        public boolean isOpen()
        {
            if (upgraded)
                return super.isOpen();
            else
                return plainEndPoint.isOpen();
        }

        public Object getTransport()
        {
            if (upgraded)
                return super.getTransport();
            else
                return plainEndPoint.getTransport();
        }

        public boolean isBufferingInput()
        {
            if (upgraded)
                return super.isBufferingInput();
            else
                return plainEndPoint.isBufferingInput();
        }

        public boolean isBufferingOutput()
        {
            if (upgraded)
                return super.isBufferingOutput();
            else
                return plainEndPoint.isBufferingOutput();
        }

        public void flush() throws IOException
        {
            if (upgraded)
                super.flush();
            else
                plainEndPoint.flush();

        }

        public int getMaxIdleTime()
        {
            if (upgraded)
                return super.getMaxIdleTime();
            else
                return plainEndPoint.getMaxIdleTime();
        }

        public void setMaxIdleTime(int timeMs) throws IOException
        {
            if (upgraded)
                super.setMaxIdleTime(timeMs);
            else
                plainEndPoint.setMaxIdleTime(timeMs);
        }
    }
}
