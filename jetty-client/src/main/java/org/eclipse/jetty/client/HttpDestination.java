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
import java.lang.reflect.Constructor;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;

import org.eclipse.jetty.client.HttpClient.Connector;
import org.eclipse.jetty.client.security.Authentication;
import org.eclipse.jetty.client.security.SecurityListener;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.PathMap;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * @version $Revision: 879 $ $Date: 2009-09-11 16:13:28 +0200 (Fri, 11 Sep 2009) $
 */
public class HttpDestination implements Dumpable
{
    private static final Logger LOG = Log.getLogger(HttpDestination.class);

    private final List<HttpExchange> _queue = new LinkedList<HttpExchange>();
    private final List<HttpConnection> _connections = new LinkedList<HttpConnection>();
    private final BlockingQueue<Object> _newQueue = new ArrayBlockingQueue<Object>(10, true);
    private final List<HttpConnection> _idle = new ArrayList<HttpConnection>();
    private final HttpClient _client;
    private final Address _address;
    private final boolean _ssl;
    private final ByteArrayBuffer _hostHeader;
    private volatile int _maxConnections;
    private volatile int _maxQueueSize;
    private int _pendingConnections = 0;
    private int _newConnection = 0;
    private volatile Address _proxy;
    private Authentication _proxyAuthentication;
    private PathMap _authorizations;
    private List<HttpCookie> _cookies;



    HttpDestination(HttpClient client, Address address, boolean ssl)
    {
        _client = client;
        _address = address;
        _ssl = ssl;
        _maxConnections = _client.getMaxConnectionsPerAddress();
        _maxQueueSize = _client.getMaxQueueSizePerAddress();
        String addressString = address.getHost();
        if (address.getPort() != (_ssl ? 443 : 80))
            addressString += ":" + address.getPort();
        _hostHeader = new ByteArrayBuffer(addressString);
    }

    public HttpClient getHttpClient()
    {
        return _client;
    }

    public Address getAddress()
    {
        return _address;
    }

    public boolean isSecure()
    {
        return _ssl;
    }

    public Buffer getHostHeader()
    {
        return _hostHeader;
    }

    public int getMaxConnections()
    {
        return _maxConnections;
    }

    public void setMaxConnections(int maxConnections)
    {
        this._maxConnections = maxConnections;
    }

    public int getMaxQueueSize()
    {
        return _maxQueueSize;
    }

    public void setMaxQueueSize(int maxQueueSize)
    {
        this._maxQueueSize = maxQueueSize;
    }

    public int getConnections()
    {
        synchronized (this)
        {
            return _connections.size();
        }
    }

    public int getIdleConnections()
    {
        synchronized (this)
        {
            return _idle.size();
        }
    }

    public void addAuthorization(String pathSpec, Authentication authorization)
    {
        synchronized (this)
        {
            if (_authorizations == null)
                _authorizations = new PathMap();
            _authorizations.put(pathSpec, authorization);
        }

        // TODO query and remove methods
    }

    public void addCookie(HttpCookie cookie)
    {
        synchronized (this)
        {
            if (_cookies == null)
                _cookies = new ArrayList<HttpCookie>();
            _cookies.add(cookie);
        }

        // TODO query, remove and age methods
    }

    /**
     * Get a connection. We either get an idle connection if one is available, or
     * we make a new connection, if we have not yet reached maxConnections. If we
     * have reached maxConnections, we wait until the number reduces.
     *
     * @param timeout max time prepared to block waiting to be able to get a connection
     * @return a HttpConnection for this destination
     * @throws IOException if an I/O error occurs
     */
    private HttpConnection getConnection(long timeout) throws IOException
    {
        HttpConnection connection = null;

        while ((connection == null) && (connection = getIdleConnection()) == null && timeout > 0)
        {
            boolean startConnection = false;
            synchronized (this)
            {
                int totalConnections = _connections.size() + _pendingConnections;
                if (totalConnections < _maxConnections)
                {
                    _newConnection++;
                    startConnection = true;
                }
            }

            if (startConnection)
            {
                startNewConnection();
                try
                {
                    Object o = _newQueue.take();
                    if (o instanceof HttpConnection)
                    {
                        connection = (HttpConnection)o;
                    }
                    else
                        throw (IOException)o;
                }
                catch (InterruptedException e)
                {
                    LOG.ignore(e);
                }
            }
            else
            {
                try
                {
                    Thread.currentThread();
                    Thread.sleep(200);
                    timeout -= 200;
                }
                catch (InterruptedException e)
                {
                    LOG.ignore(e);
                }
            }
        }
        return connection;
    }

    public HttpConnection reserveConnection(long timeout) throws IOException
    {
        HttpConnection connection = getConnection(timeout);
        if (connection != null)
            connection.setReserved(true);
        return connection;
    }

    public HttpConnection getIdleConnection() throws IOException
    {
        HttpConnection connection = null;
        while (true)
        {
            synchronized (this)
            {
                if (connection != null)
                {
                    _connections.remove(connection);
                    connection.close();
                    connection = null;
                }
                if (_idle.size() > 0)
                    connection = _idle.remove(_idle.size() - 1);
            }

            if (connection == null)
                return null;

            // Check if the connection was idle,
            // but it expired just a moment ago
            if (connection.cancelIdleTimeout())
                return connection;
        }
    }

    protected void startNewConnection()
    {
        try
        {
            synchronized (this)
            {
                _pendingConnections++;
            }
            final Connector connector = _client._connector;
            if (connector != null)
                connector.startConnection(this);
        }
        catch (Exception e)
        {
            LOG.debug(e);
            onConnectionFailed(e);
        }
    }

    public void onConnectionFailed(Throwable throwable)
    {
        Throwable connect_failure = null;

        boolean startConnection = false;
        synchronized (this)
        {
            _pendingConnections--;
            if (_newConnection > 0)
            {
                connect_failure = throwable;
                _newConnection--;
            }
            else if (_queue.size() > 0)
            {
                HttpExchange ex = _queue.remove(0);
                ex.setStatus(HttpExchange.STATUS_EXCEPTED);
                ex.getEventListener().onConnectionFailed(throwable);

                // Since an existing connection had failed, we need to create a
                // connection if the  queue is not empty and client is running.
                if (!_queue.isEmpty() && _client.isStarted())
                    startConnection = true;
            }
        }

        if (startConnection)
            startNewConnection();

        if (connect_failure != null)
        {
            try
            {
                _newQueue.put(connect_failure);
            }
            catch (InterruptedException e)
            {
                LOG.ignore(e);
            }
        }
    }

    public void onException(Throwable throwable)
    {
        synchronized (this)
        {
            _pendingConnections--;
            if (_queue.size() > 0)
            {
                HttpExchange ex = _queue.remove(0);
                ex.setStatus(HttpExchange.STATUS_EXCEPTED);
                ex.getEventListener().onException(throwable);
            }
        }
    }

    public void onNewConnection(final HttpConnection connection) throws IOException
    {
        Connection q_connection = null;

        synchronized (this)
        {
            _pendingConnections--;
            _connections.add(connection);

            if (_newConnection > 0)
            {
                q_connection = connection;
                _newConnection--;
            }
            else if (_queue.size() == 0)
            {
                connection.setIdleTimeout();
                _idle.add(connection);
            }
            else
            {
                EndPoint endPoint = connection.getEndPoint();
                if (isProxied() && endPoint instanceof SelectConnector.ProxySelectChannelEndPoint)
                {
                    SelectConnector.ProxySelectChannelEndPoint proxyEndPoint = (SelectConnector.ProxySelectChannelEndPoint)endPoint;
                    HttpExchange exchange = _queue.get(0);
                    ConnectExchange connect = new ConnectExchange(getAddress(), proxyEndPoint, exchange);
                    connect.setAddress(getProxy());
                    send(connection, connect);
                }
                else
                {
                    HttpExchange exchange = _queue.remove(0);
                    send(connection, exchange);
                }
            }
        }

        if (q_connection != null)
        {
            try
            {
                _newQueue.put(q_connection);
            }
            catch (InterruptedException e)
            {
                LOG.ignore(e);
            }
        }
    }

    public void returnConnection(HttpConnection connection, boolean close) throws IOException
    {
        if (connection.isReserved())
            connection.setReserved(false);

        if (close)
        {
            try
            {
                connection.close();
            }
            catch (IOException e)
            {
                LOG.ignore(e);
            }
        }

        if (!_client.isStarted())
            return;

        if (!close && connection.getEndPoint().isOpen())
        {
            synchronized (this)
            {
                if (_queue.size() == 0)
                {
                    connection.setIdleTimeout();
                    _idle.add(connection);
                }
                else
                {
                    HttpExchange ex = _queue.remove(0);
                    send(connection, ex);
                }
                this.notifyAll();
            }
        }
        else
        {
            boolean startConnection = false;
            synchronized (this)
            {
                _connections.remove(connection);
                if (!_queue.isEmpty())
                    startConnection = true;
            }

            if (startConnection)
                startNewConnection();
        }
    }

    public void returnIdleConnection(HttpConnection connection)
    {
        try
        {
            connection.close();
        }
        catch (IOException e)
        {
            LOG.ignore(e);
        }

        boolean startConnection = false;
        synchronized (this)
        {
            _idle.remove(connection);
            _connections.remove(connection);

            if (!_queue.isEmpty() && _client.isStarted())
                startConnection = true;
        }

        if (startConnection)
            startNewConnection();
    }

    public void send(HttpExchange ex) throws IOException
    {
        LinkedList<String> listeners = _client.getRegisteredListeners();

        if (listeners != null)
        {
            // Add registered listeners, fail if we can't load them
            for (int i = listeners.size(); i > 0; --i)
            {
                String listenerClass = listeners.get(i - 1);

                try
                {
                    Class listener = Class.forName(listenerClass);
                    Constructor constructor = listener.getDeclaredConstructor(HttpDestination.class, HttpExchange.class);
                    HttpEventListener elistener = (HttpEventListener)constructor.newInstance(this, ex);
                    ex.setEventListener(elistener);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    throw new IOException("Unable to instantiate registered listener for destination: " + listenerClass);
                }
            }
        }

        // Security is supported by default and should be the first consulted
        if (_client.hasRealms())
        {
            ex.setEventListener(new SecurityListener(this, ex));
        }

        doSend(ex);
    }

    public void resend(HttpExchange ex) throws IOException
    {
        ex.getEventListener().onRetry();
        ex.reset();
        doSend(ex);
    }

    protected void doSend(HttpExchange ex) throws IOException
    {
        // add cookies
        // TODO handle max-age etc.
        if (_cookies != null)
        {
            StringBuilder buf = null;
            for (HttpCookie cookie : _cookies)
            {
                if (buf == null)
                    buf = new StringBuilder();
                else
                    buf.append("; ");
                buf.append(cookie.getName()); // TODO quotes
                buf.append("=");
                buf.append(cookie.getValue()); // TODO quotes
            }
            if (buf != null)
                ex.addRequestHeader(HttpHeaders.COOKIE, buf.toString());
        }

        // Add any known authorizations
        if (_authorizations != null)
        {
            Authentication auth = (Authentication)_authorizations.match(ex.getRequestURI());
            if (auth != null)
                (auth).setCredentials(ex);
        }

        // Schedule the timeout here, before we queue the exchange
        // so that we count also the queue time in the timeout
        ex.scheduleTimeout(this);

        HttpConnection connection = getIdleConnection();
        if (connection != null)
        {
            send(connection, ex);
        }
        else
        {
            boolean startConnection = false;
            synchronized (this)
            {
                if (_queue.size() == _maxQueueSize)
                    throw new RejectedExecutionException("Queue full for address " + _address);

                _queue.add(ex);
                if (_connections.size() + _pendingConnections < _maxConnections)
                    startConnection = true;
            }

            if (startConnection)
                startNewConnection();
        }
    }

    protected void exchangeExpired(HttpExchange exchange)
    {
        // The exchange may expire while waiting in the
        // destination queue, make sure it is removed
        synchronized (this)
        {
            _queue.remove(exchange);
        }
    }

    protected void send(HttpConnection connection, HttpExchange exchange) throws IOException
    {
        synchronized (this)
        {
            // If server closes the connection, put the exchange back
            // to the exchange queue and recycle the connection
            if (!connection.send(exchange))
            {
                if (exchange.getStatus() <= HttpExchange.STATUS_WAITING_FOR_CONNECTION)
                    _queue.add(0, exchange);
                returnIdleConnection(connection);
            }
        }
    }

    @Override
    public synchronized String toString()
    {
        return String.format("HttpDestination@%x//%s:%d(%d/%d,%d,%d/%d)%n",hashCode(),_address.getHost(),_address.getPort(),_connections.size(),_maxConnections,_idle.size(),_queue.size(),_maxQueueSize);
    }

    public synchronized String toDetailString()
    {
        StringBuilder b = new StringBuilder();
        b.append(toString());
        b.append('\n');
        synchronized (this)
        {
            for (HttpConnection connection : _connections)
            {
                b.append(connection.toDetailString());
                if (_idle.contains(connection))
                    b.append(" IDLE");
                b.append('\n');
            }
        }
        b.append("--");
        b.append('\n');

        return b.toString();
    }

    public void setProxy(Address proxy)
    {
        _proxy = proxy;
    }

    public Address getProxy()
    {
        return _proxy;
    }

    public Authentication getProxyAuthentication()
    {
        return _proxyAuthentication;
    }

    public void setProxyAuthentication(Authentication authentication)
    {
        _proxyAuthentication = authentication;
    }

    public boolean isProxied()
    {
        return _proxy != null;
    }

    public void close() throws IOException
    {
        synchronized (this)
        {
            for (HttpConnection connection : _connections)
            {
                connection.close();
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.util.component.Dumpable#dump()
     */
    public String dump()
    {
        return AggregateLifeCycle.dump(this);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.util.component.Dumpable#dump(java.lang.Appendable, java.lang.String)
     */
    public void dump(Appendable out, String indent) throws IOException
    {
        synchronized (this)
        {
            out.append(String.valueOf(this)+"idle="+_idle.size()+" pending="+_pendingConnections).append("\n");
            AggregateLifeCycle.dump(out,indent,_connections);
        }
    }

    private class ConnectExchange extends ContentExchange
    {
        private final SelectConnector.ProxySelectChannelEndPoint proxyEndPoint;
        private final HttpExchange exchange;

        public ConnectExchange(Address serverAddress, SelectConnector.ProxySelectChannelEndPoint proxyEndPoint, HttpExchange exchange)
        {
            this.proxyEndPoint = proxyEndPoint;
            this.exchange = exchange;
            setMethod(HttpMethods.CONNECT);
            setVersion(exchange.getVersion());
            String serverHostAndPort = serverAddress.toString();
            setRequestURI(serverHostAndPort);
            addRequestHeader(HttpHeaders.HOST, serverHostAndPort);
            addRequestHeader(HttpHeaders.PROXY_CONNECTION, "keep-alive");
            addRequestHeader(HttpHeaders.USER_AGENT, "Jetty-Client");
        }

        @Override
        protected void onResponseComplete() throws IOException
        {
            int responseStatus = getResponseStatus();
            if (responseStatus == HttpStatus.OK_200)
            {
                proxyEndPoint.upgrade();
            }
            else if(responseStatus == HttpStatus.GATEWAY_TIMEOUT_504){
                onExpire();
            }
            else
            {
                onException(new ConnectException("Proxy: " + proxyEndPoint.getRemoteAddr() +":" + proxyEndPoint.getRemotePort() + " didn't return http return code 200, but " + responseStatus + " while trying to request: " + exchange.getAddress().toString()));
            }
        }

        @Override
        protected void onConnectionFailed(Throwable x)
        {
            HttpDestination.this.onConnectionFailed(x);
        }

        @Override
        protected void onException(Throwable x)
        {
            _queue.remove(exchange);
            exchange.setStatus(STATUS_EXCEPTED);
            exchange.getEventListener().onException(x);
        }

        @Override
        protected void onExpire()
        {
            _queue.remove(exchange);
            exchange.setStatus(STATUS_EXPIRED);
            exchange.getEventListener().onExpire();
        }

    }
}
