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
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

import org.eclipse.jetty.client.security.Authorization;
import org.eclipse.jetty.client.security.SecurityListener;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.PathMap;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.util.log.Log;

/**
 *
 * @version $Revision: 879 $ $Date: 2009-09-11 16:13:28 +0200 (Fri, 11 Sep 2009) $
 */
public class HttpDestination
{
    private final ByteArrayBuffer _hostHeader;
    private final Address _address;
    private final LinkedList<HttpConnection> _connections = new LinkedList<HttpConnection>();
    private final ArrayList<HttpConnection> _idle = new ArrayList<HttpConnection>();
    private final HttpClient _client;
    private final boolean _ssl;
    private final int _maxConnections;
    private int _pendingConnections = 0;
    private ArrayBlockingQueue<Object> _newQueue = new ArrayBlockingQueue<Object>(10, true);
    private int _newConnection = 0;
    private Address _proxy;
    private Authorization _proxyAuthentication;
    private PathMap _authorizations;
    private List<HttpCookie> _cookies;

    public void dump() throws IOException
    {
        synchronized (this)
        {
            Log.info(this.toString());
            Log.info("connections=" + _connections.size());
            Log.info("idle=" + _idle.size());
            Log.info("pending=" + _pendingConnections);
            for (HttpConnection c : _connections)
            {
                if (!c.isIdle())
                    c.dump();
            }
        }
    }

    /* The queue of exchanged for this destination if connections are limited */
    private LinkedList<HttpExchange> _queue = new LinkedList<HttpExchange>();

    HttpDestination(HttpClient client, Address address, boolean ssl, int maxConnections)
    {
        _client = client;
        _address = address;
        _ssl = ssl;
        _maxConnections = maxConnections;
        String addressString = address.getHost();
        if (address.getPort() != (_ssl ? 443 : 80)) addressString += ":" + address.getPort();
        _hostHeader = new ByteArrayBuffer(addressString);
    }

    public Address getAddress()
    {
        return _address;
    }

    public Buffer getHostHeader()
    {
        return _hostHeader;
    }

    public HttpClient getHttpClient()
    {
        return _client;
    }

    public boolean isSecure()
    {
        return _ssl;
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
    
    public void addAuthorization(String pathSpec, Authorization authorization)
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
     * @param timeout max time prepared to block waiting to be able to get a connection
     * @return
     * @throws IOException
     */
    private HttpConnection getConnection(long timeout) throws IOException
    {
        HttpConnection connection = null;

        while ((connection == null) && (connection = getIdleConnection()) == null && timeout>0)
        {
            int totalConnections = 0;
            boolean starting = false;
            synchronized (this)
            {
                totalConnections = _connections.size() + _pendingConnections;
                if (totalConnections < _maxConnections)
                {
                    _newConnection++;
                    startNewConnection();
                    starting = true;
                }
            }

            if (!starting)
            {
                try
                {
                    Thread.currentThread();
                    Thread.sleep(200);
                    timeout-=200;
                }
                catch (InterruptedException e)
                {
                    Log.ignore(e);
                }
            }
            else
            {
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
                   Log.ignore(e);
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
        long now = _client.getNow();
        long idleTimeout=_client.getIdleTimeout();
        HttpConnection connection = null;
        while (true)
        {
            synchronized (this)
            {
                if (connection!=null)
                {
                    _connections.remove(connection);
                    connection.getEndPoint().close();
                    connection=null;
                }
                if (_idle.size() > 0)
                    connection = _idle.remove(_idle.size()-1);
            }

            if (connection==null)
                return null;

            if (connection.cancelIdleTimeout() )
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
            _client._connector.startConnection(this);
        }
        catch (Exception e)
        {
            Log.debug(e);
            onConnectionFailed(e);
        }
    }

    public void onConnectionFailed(Throwable throwable)
    {
        Throwable connect_failure = null;

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
                HttpExchange ex = _queue.removeFirst();
                ex.setStatus(HttpExchange.STATUS_EXCEPTED);
                ex.getEventListener().onConnectionFailed(throwable);
            }
        }

        if (connect_failure != null)
        {
            try
            {
                _newQueue.put(connect_failure);
            }
            catch (InterruptedException e)
            {
                Log.ignore(e);
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
                HttpExchange ex = _queue.removeFirst();
                ex.setStatus(HttpExchange.STATUS_EXCEPTED);
                ex.getEventListener().onException(throwable);
            }
        }
    }

    public void onNewConnection(HttpConnection connection) throws IOException
    {
        HttpConnection q_connection = null;

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
                _idle.add(connection);
            }
            else
            {
                HttpExchange ex = _queue.removeFirst();
                connection.send(ex);
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
                Log.ignore(e);
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
                Log.ignore(e);
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
                    connection.setIdleTimeout(_client.getNow()+_client.getIdleTimeout());
                    _idle.add(connection);
                }
                else
                {
                    HttpExchange ex = _queue.removeFirst();
                    connection.send(ex);
                }
                this.notifyAll();
            }
        }
        else
        {
            synchronized (this)
            {
                _connections.remove(connection);
                if (!_queue.isEmpty())
                    startNewConnection();
            }
        }
    }

    public void returnIdleConnection(HttpConnection connection) throws IOException
    {
        try
        {
            connection.close();
        }
        catch (IOException e)
        {
            Log.ignore(e);
        }

        synchronized (this)
        {
            _idle.remove(connection);
            _connections.remove(connection);
            if (!_queue.isEmpty() && _client.isStarted())
                startNewConnection();
        }
        
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
            Authorization auth = (Authorization)_authorizations.match(ex.getURI());
            if (auth != null)
                (auth).setCredentials(ex);
        }

        HttpConnection connection = getIdleConnection();
        if (connection != null)
        {
            boolean sent = connection.send(ex);
            if (!sent) connection = null;
        }

        if (connection == null)
        {
            synchronized (this)
            {
                _queue.add(ex);
                if (_connections.size() + _pendingConnections < _maxConnections)
                {
                    startNewConnection();
                }
            }
        }
    }

    @Override
    public synchronized String toString()
    {
        return "HttpDestination@" + hashCode() + "//" + _address.getHost() + ":" + _address.getPort() + "(" + _connections.size() + "," + _idle.size() + "," + _queue.size() + ")";
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

    public Authorization getProxyAuthentication()
    {
        return _proxyAuthentication;
    }

    public void setProxyAuthentication(Authorization authentication)
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
}
