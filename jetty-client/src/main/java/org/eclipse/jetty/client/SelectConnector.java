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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.UnresolvedAddressException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.nio.AsyncConnection;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.io.nio.SelectorManager;
import org.eclipse.jetty.io.nio.SslConnection;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Timeout;
import org.eclipse.jetty.util.thread.Timeout.Task;

class SelectConnector extends AggregateLifeCycle implements HttpClient.Connector, Dumpable
{
    private static final Logger LOG = Log.getLogger(SelectConnector.class);

    private final HttpClient _httpClient;
    private final Manager _selectorManager=new Manager();
    private final Map<SocketChannel, Timeout.Task> _connectingChannels = new ConcurrentHashMap<SocketChannel, Timeout.Task>();

    /* ------------------------------------------------------------ */
    /**
     * @param httpClient the HttpClient this connector is associated to. It is 
     * added via the {@link #addBean(Object, boolean)} as an unmanaged bean.
     */
    SelectConnector(HttpClient httpClient)
    {
        _httpClient = httpClient;
        addBean(_httpClient,false);
        addBean(_selectorManager,true);
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
        Logger LOG = SelectConnector.LOG;

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
        public AsyncConnection newConnection(SocketChannel channel, AsyncEndPoint endpoint, Object attachment)
        {
            return new AsyncHttpConnection(_httpClient.getRequestBuffers(),_httpClient.getResponseBuffers(),endpoint);
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

            SelectChannelEndPoint scep = new SelectChannelEndPoint(channel, selectSet, key, (int)_httpClient.getIdleTimeout());
            AsyncEndPoint ep = scep;

            if (dest.isSecure())
            {
                LOG.debug("secure to {}, proxied={}",channel,dest.isProxied());
                ep = new UpgradableEndPoint(ep,newSslEngine(dest.getSslContextFactory(), channel));
            }

            AsyncConnection connection = selectSet.getManager().newConnection(channel,ep, key.attachment());
            ep.setConnection(connection);

            AbstractHttpConnection httpConnection=(AbstractHttpConnection)connection;
            httpConnection.setDestination(dest);

            if (dest.isSecure() && !dest.isProxied())
                ((UpgradableEndPoint)ep).upgrade();

            dest.onNewConnection(httpConnection);

            return scep;
        }

        private synchronized SSLEngine newSslEngine(SslContextFactory sslContextFactory, SocketChannel channel) throws IOException
        {
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
                close();
                _connectingChannels.remove(channel);
                destination.onConnectionFailed(new SocketTimeoutException());
            }
        }

        private void close()
        {
            try
            {
                // This will unregister the channel from the selector
                channel.close();
            }
            catch (IOException x)
            {
                LOG.ignore(x);
            }
        }
    }

    public static class UpgradableEndPoint implements AsyncEndPoint
    {
        AsyncEndPoint _endp;
        SSLEngine _engine;

        public UpgradableEndPoint(AsyncEndPoint endp, SSLEngine engine) throws IOException
        {
            _engine=engine;
            _endp=endp;
        }

        public void upgrade()
        {
            AsyncHttpConnection connection = (AsyncHttpConnection)_endp.getConnection();

            SslConnection sslConnection = new SslConnection(_engine,_endp);
            _endp.setConnection(sslConnection);

            _endp=sslConnection.getSslEndPoint();
            sslConnection.getSslEndPoint().setConnection(connection);

            LOG.debug("upgrade {} to {} for {}",this,sslConnection,connection);
        }


        public Connection getConnection()
        {
            return _endp.getConnection();
        }

        public void setConnection(Connection connection)
        {
            _endp.setConnection(connection);
        }

        public void shutdownOutput() throws IOException
        {
            _endp.shutdownOutput();
        }

        public void dispatch()
        {
            _endp.asyncDispatch();
        }

        public void asyncDispatch()
        {
            _endp.asyncDispatch();
        }

        public boolean isOutputShutdown()
        {
            return _endp.isOutputShutdown();
        }

        public void shutdownInput() throws IOException
        {
            _endp.shutdownInput();
        }

        public void scheduleWrite()
        {
            _endp.scheduleWrite();
        }

        public boolean isInputShutdown()
        {
            return _endp.isInputShutdown();
        }

        public void close() throws IOException
        {
            _endp.close();
        }

        public int fill(Buffer buffer) throws IOException
        {
            return _endp.fill(buffer);
        }

        public boolean isWritable()
        {
            return _endp.isWritable();
        }

        public boolean hasProgressed()
        {
            return _endp.hasProgressed();
        }

        public int flush(Buffer buffer) throws IOException
        {
            return _endp.flush(buffer);
        }

        public void scheduleTimeout(Task task, long timeoutMs)
        {
            _endp.scheduleTimeout(task,timeoutMs);
        }

        public void cancelTimeout(Task task)
        {
            _endp.cancelTimeout(task);
        }

        public int flush(Buffer header, Buffer buffer, Buffer trailer) throws IOException
        {
            return _endp.flush(header,buffer,trailer);
        }

        public String getLocalAddr()
        {
            return _endp.getLocalAddr();
        }

        public String getLocalHost()
        {
            return _endp.getLocalHost();
        }

        public int getLocalPort()
        {
            return _endp.getLocalPort();
        }

        public String getRemoteAddr()
        {
            return _endp.getRemoteAddr();
        }

        public String getRemoteHost()
        {
            return _endp.getRemoteHost();
        }

        public int getRemotePort()
        {
            return _endp.getRemotePort();
        }

        public boolean isBlocking()
        {
            return _endp.isBlocking();
        }

        public boolean blockReadable(long millisecs) throws IOException
        {
            return _endp.blockReadable(millisecs);
        }

        public boolean blockWritable(long millisecs) throws IOException
        {
            return _endp.blockWritable(millisecs);
        }

        public boolean isOpen()
        {
            return _endp.isOpen();
        }

        public Object getTransport()
        {
            return _endp.getTransport();
        }

        public void flush() throws IOException
        {
            _endp.flush();
        }

        public int getMaxIdleTime()
        {
            return _endp.getMaxIdleTime();
        }

        public void setMaxIdleTime(int timeMs) throws IOException
        {
            _endp.setMaxIdleTime(timeMs);
        }

        public void onIdleExpired(long idleForMs)
        {
            _endp.onIdleExpired(idleForMs);
        }

        public void setCheckForIdle(boolean check)
        {
            _endp.setCheckForIdle(check);
        }

        public boolean isCheckForIdle()
        {
            return _endp.isCheckForIdle();
        }

        public String toString()
        {
            return "Upgradable:"+_endp.toString();
        }
    }
}
