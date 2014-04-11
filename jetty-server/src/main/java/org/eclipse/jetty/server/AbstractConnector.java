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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.ServletRequest;

import org.eclipse.jetty.http.HttpBuffers;
import org.eclipse.jetty.http.HttpBuffersImpl;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpSchemes;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.Buffers.Type;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.statistic.CounterStatistic;
import org.eclipse.jetty.util.statistic.SampleStatistic;
import org.eclipse.jetty.util.thread.ThreadPool;

/**
 * Abstract Connector implementation. This abstract implementation of the Connector interface provides:
 * <ul>
 * <li>AbstractLifeCycle implementation</li>
 * <li>Implementations for connector getters and setters</li>
 * <li>Buffer management</li>
 * <li>Socket configuration</li>
 * <li>Base acceptor thread</li>
 * <li>Optional reverse proxy headers checking</li>
 * </ul>
 */
public abstract class AbstractConnector extends AggregateLifeCycle implements HttpBuffers, Connector, Dumpable
{
    private static final Logger LOG = Log.getLogger(AbstractConnector.class);

    private String _name;

    private Server _server;
    private ThreadPool _threadPool;
    private String _host;
    private int _port = 0;
    private String _integralScheme = HttpSchemes.HTTPS;
    private int _integralPort = 0;
    private String _confidentialScheme = HttpSchemes.HTTPS;
    private int _confidentialPort = 0;
    private int _acceptQueueSize = 0;
    private int _acceptors = 1;
    private int _acceptorPriorityOffset = 0;
    private boolean _useDNS;
    private boolean _forwarded;
    private String _hostHeader;

    private String _forwardedHostHeader = HttpHeaders.X_FORWARDED_HOST;
    private String _forwardedServerHeader = HttpHeaders.X_FORWARDED_SERVER;
    private String _forwardedForHeader = HttpHeaders.X_FORWARDED_FOR;
    private String _forwardedProtoHeader = HttpHeaders.X_FORWARDED_PROTO;
    private String _forwardedCipherSuiteHeader;
    private String _forwardedSslSessionIdHeader;
    private boolean _reuseAddress = true;

    protected int _maxIdleTime = 200000;
    protected int _lowResourceMaxIdleTime = -1;
    protected int _soLingerTime = -1;

    private transient Thread[] _acceptorThreads;

    private final AtomicLong _statsStartedAt = new AtomicLong(-1L);

    /** connections to server */
    private final CounterStatistic _connectionStats = new CounterStatistic();
    /** requests per connection */
    private final SampleStatistic _requestStats = new SampleStatistic();
    /** duration of a connection */
    private final SampleStatistic _connectionDurationStats = new SampleStatistic();

    protected final HttpBuffersImpl _buffers = new HttpBuffersImpl();

    /* ------------------------------------------------------------ */
    /**
     */
    public AbstractConnector()
    {
        addBean(_buffers);
    }

    /* ------------------------------------------------------------ */
    /*
     */
    public Server getServer()
    {
        return _server;
    }

    /* ------------------------------------------------------------ */
    public void setServer(Server server)
    {
        _server = server;
    }

    /* ------------------------------------------------------------ */
    public ThreadPool getThreadPool()
    {
        return _threadPool;
    }

    /* ------------------------------------------------------------ */
    /** Set the ThreadPool.
     * The threadpool passed is added via {@link #addBean(Object)} so that 
     * it's lifecycle may be managed as a {@link AggregateLifeCycle}.
     * @param pool the threadPool to set
     */
    public void setThreadPool(ThreadPool pool)
    {
        removeBean(_threadPool);
        _threadPool = pool;
        addBean(_threadPool);
    }

    /* ------------------------------------------------------------ */
    /**
     */
    public void setHost(String host)
    {
        _host = host;
    }

    /* ------------------------------------------------------------ */
    /*
     */
    public String getHost()
    {
        return _host;
    }

    /* ------------------------------------------------------------ */
    public void setPort(int port)
    {
        _port = port;
    }

    /* ------------------------------------------------------------ */
    public int getPort()
    {
        return _port;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the maxIdleTime.
     */
    public int getMaxIdleTime()
    {
        return _maxIdleTime;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the maximum Idle time for a connection, which roughly translates to the {@link Socket#setSoTimeout(int)} call, although with NIO implementations
     * other mechanisms may be used to implement the timeout. The max idle time is applied:
     * <ul>
     * <li>When waiting for a new request to be received on a connection</li>
     * <li>When reading the headers and content of a request</li>
     * <li>When writing the headers and content of a response</li>
     * </ul>
     * Jetty interprets this value as the maximum time between some progress being made on the connection. So if a single byte is read or written, then the
     * timeout (if implemented by jetty) is reset. However, in many instances, the reading/writing is delegated to the JVM, and the semantic is more strictly
     * enforced as the maximum time a single read/write operation can take. Note, that as Jetty supports writes of memory mapped file buffers, then a write may
     * take many 10s of seconds for large content written to a slow device.
     * <p>
     * Previously, Jetty supported separate idle timeouts and IO operation timeouts, however the expense of changing the value of soTimeout was significant, so
     * these timeouts were merged. With the advent of NIO, it may be possible to again differentiate these values (if there is demand).
     *
     * @param maxIdleTime
     *            The maxIdleTime to set.
     */
    public void setMaxIdleTime(int maxIdleTime)
    {
        _maxIdleTime = maxIdleTime;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the maxIdleTime when resources are low.
     */
    public int getLowResourcesMaxIdleTime()
    {
        return _lowResourceMaxIdleTime;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param maxIdleTime
     *            The maxIdleTime to set when resources are low.
     */
    public void setLowResourcesMaxIdleTime(int maxIdleTime)
    {
        _lowResourceMaxIdleTime = maxIdleTime;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the maxIdleTime when resources are low.
     * @deprecated
     */
    @Deprecated
    public final int getLowResourceMaxIdleTime()
    {
        return getLowResourcesMaxIdleTime();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param maxIdleTime
     *            The maxIdleTime to set when resources are low.
     * @deprecated
     */
    @Deprecated
    public final void setLowResourceMaxIdleTime(int maxIdleTime)
    {
        setLowResourcesMaxIdleTime(maxIdleTime);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the soLingerTime.
     */
    public int getSoLingerTime()
    {
        return _soLingerTime;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the acceptQueueSize.
     */
    public int getAcceptQueueSize()
    {
        return _acceptQueueSize;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param acceptQueueSize
     *            The acceptQueueSize to set.
     */
    public void setAcceptQueueSize(int acceptQueueSize)
    {
        _acceptQueueSize = acceptQueueSize;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the number of acceptor threads.
     */
    public int getAcceptors()
    {
        return _acceptors;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param acceptors
     *            The number of acceptor threads to set.
     */
    public void setAcceptors(int acceptors)
    {
        if (acceptors > 2 * Runtime.getRuntime().availableProcessors())
            LOG.warn("Acceptors should be <=2*availableProcessors: " + this);
        _acceptors = acceptors;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param soLingerTime
     *            The soLingerTime to set or -1 to disable.
     */
    public void setSoLingerTime(int soLingerTime)
    {
        _soLingerTime = soLingerTime;
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStart() throws Exception
    {
        if (_server == null)
            throw new IllegalStateException("No server");

        // open listener port
        open();

        if (_threadPool == null)
        {
            _threadPool = _server.getThreadPool();
            addBean(_threadPool,false);
        }

        super.doStart();

        // Start selector thread
        synchronized (this)
        {
            _acceptorThreads = new Thread[getAcceptors()];

            for (int i = 0; i < _acceptorThreads.length; i++)
                if (!_threadPool.dispatch(new Acceptor(i)))
                    throw new IllegalStateException("!accepting");
            if (_threadPool.isLowOnThreads())
                LOG.warn("insufficient threads configured for {}",this);
        }

        LOG.info("Started {}",this);
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStop() throws Exception
    {
        try
        {
            close();
        }
        catch (IOException e)
        {
            LOG.warn(e);
        }

        super.doStop();

        Thread[] acceptors;
        synchronized (this)
        {
            acceptors = _acceptorThreads;
            _acceptorThreads = null;
        }
        if (acceptors != null)
        {
            for (Thread thread : acceptors)
            {
                if (thread != null)
                    thread.interrupt();
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void join() throws InterruptedException
    {
        Thread[] threads;
        synchronized(this)
        {
            threads=_acceptorThreads;
        }
        if (threads != null)
            for (Thread thread : threads)
                if (thread != null)
                    thread.join();
    }

    /* ------------------------------------------------------------ */
    protected void configure(Socket socket) throws IOException
    {
        try
        {
            socket.setTcpNoDelay(true);
            if (_soLingerTime >= 0)
                socket.setSoLinger(true,_soLingerTime / 1000);
            else
                socket.setSoLinger(false,0);
        }
        catch (Exception e)
        {
            LOG.ignore(e);
        }
    }

    /* ------------------------------------------------------------ */
    public void customize(EndPoint endpoint, Request request) throws IOException
    {
        if (isForwarded())
            checkForwardedHeaders(endpoint,request);
    }

    /* ------------------------------------------------------------ */
    protected void checkForwardedHeaders(EndPoint endpoint, Request request) throws IOException
    {
        HttpFields httpFields = request.getConnection().getRequestFields();

        // Do SSL first
        if (getForwardedCipherSuiteHeader()!=null)
        {
            String cipher_suite=httpFields.getStringField(getForwardedCipherSuiteHeader());
            if (cipher_suite!=null)
                request.setAttribute("javax.servlet.request.cipher_suite",cipher_suite);
        }
        if (getForwardedSslSessionIdHeader()!=null)
        {
            String ssl_session_id=httpFields.getStringField(getForwardedSslSessionIdHeader());
            if(ssl_session_id!=null)
            {
                request.setAttribute("javax.servlet.request.ssl_session_id", ssl_session_id);
                request.setScheme(HttpSchemes.HTTPS);
            }
        }

        // Retrieving headers from the request
        String forwardedHost = getLeftMostFieldValue(httpFields,getForwardedHostHeader());
        String forwardedServer = getLeftMostFieldValue(httpFields,getForwardedServerHeader());
        String forwardedFor = getLeftMostFieldValue(httpFields,getForwardedForHeader());
        String forwardedProto = getLeftMostFieldValue(httpFields,getForwardedProtoHeader());

        if (_hostHeader != null)
        {
            // Update host header
            httpFields.put(HttpHeaders.HOST_BUFFER,_hostHeader);
            request.setServerName(null);
            request.setServerPort(-1);
            request.getServerName();
        }
        else if (forwardedHost != null)
        {
            // Update host header
            httpFields.put(HttpHeaders.HOST_BUFFER,forwardedHost);
            request.setServerName(null);
            request.setServerPort(-1);
            request.getServerName();
        }
        else if (forwardedServer != null)
        {
            // Use provided server name
            request.setServerName(forwardedServer);
        }

        if (forwardedFor != null)
        {
            request.setRemoteAddr(forwardedFor);
            InetAddress inetAddress = null;

            if (_useDNS)
            {
                try
                {
                    inetAddress = InetAddress.getByName(forwardedFor);
                }
                catch (UnknownHostException e)
                {
                    LOG.ignore(e);
                }
            }

            request.setRemoteHost(inetAddress == null?forwardedFor:inetAddress.getHostName());
        }

        if (forwardedProto != null)
        {
            request.setScheme(forwardedProto);
        }
    }

    /* ------------------------------------------------------------ */
    protected String getLeftMostFieldValue(HttpFields fields, String header)
    {
        if (header == null)
            return null;

        String headerValue = fields.getStringField(header);

        if (headerValue == null)
            return null;

        int commaIndex = headerValue.indexOf(',');

        if (commaIndex == -1)
        {
            // Single value
            return headerValue;
        }

        // The left-most value is the farthest downstream client
        return headerValue.substring(0,commaIndex);
    }

    /* ------------------------------------------------------------ */
    public void persist(EndPoint endpoint) throws IOException
    {
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.Connector#getConfidentialPort()
     */
    public int getConfidentialPort()
    {
        return _confidentialPort;
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.Connector#getConfidentialScheme()
     */
    public String getConfidentialScheme()
    {
        return _confidentialScheme;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.Connector#isConfidential(org.eclipse.jetty.server .Request)
     */
    public boolean isIntegral(Request request)
    {
        return false;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.Connector#getConfidentialPort()
     */
    public int getIntegralPort()
    {
        return _integralPort;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.Connector#getIntegralScheme()
     */
    public String getIntegralScheme()
    {
        return _integralScheme;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.Connector#isConfidential(org.eclipse.jetty.server.Request)
     */
    public boolean isConfidential(Request request)
    {
        return _forwarded && request.getScheme().equalsIgnoreCase(HttpSchemes.HTTPS);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param confidentialPort
     *            The confidentialPort to set.
     */
    public void setConfidentialPort(int confidentialPort)
    {
        _confidentialPort = confidentialPort;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param confidentialScheme
     *            The confidentialScheme to set.
     */
    public void setConfidentialScheme(String confidentialScheme)
    {
        _confidentialScheme = confidentialScheme;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param integralPort
     *            The integralPort to set.
     */
    public void setIntegralPort(int integralPort)
    {
        _integralPort = integralPort;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param integralScheme
     *            The integralScheme to set.
     */
    public void setIntegralScheme(String integralScheme)
    {
        _integralScheme = integralScheme;
    }

    /* ------------------------------------------------------------ */
    protected abstract void accept(int acceptorID) throws IOException, InterruptedException;

    /* ------------------------------------------------------------ */
    public void stopAccept(int acceptorID) throws Exception
    {
    }

    /* ------------------------------------------------------------ */
    public boolean getResolveNames()
    {
        return _useDNS;
    }

    /* ------------------------------------------------------------ */
    public void setResolveNames(boolean resolve)
    {
        _useDNS = resolve;
    }

    /* ------------------------------------------------------------ */
    /**
     * Is reverse proxy handling on?
     *
     * @return true if this connector is checking the x-forwarded-for/host/server headers
     */
    public boolean isForwarded()
    {
        return _forwarded;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set reverse proxy handling. If set to true, then the X-Forwarded headers (or the headers set in their place) are looked for to set the request protocol,
     * host, server and client ip.
     *
     * @param check
     *            true if this connector is checking the x-forwarded-for/host/server headers
     * @see #setForwardedForHeader(String)
     * @see #setForwardedHostHeader(String)
     * @see #setForwardedProtoHeader(String)
     * @see #setForwardedServerHeader(String)
     */
    public void setForwarded(boolean check)
    {
        if (check)
            LOG.debug("{} is forwarded",this);
        _forwarded = check;
    }

    /* ------------------------------------------------------------ */
    public String getHostHeader()
    {
        return _hostHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set a forced valued for the host header to control what is returned by {@link ServletRequest#getServerName()} and {@link ServletRequest#getServerPort()}.
     * This value is only used if {@link #isForwarded()} is true.
     *
     * @param hostHeader
     *            The value of the host header to force.
     */
    public void setHostHeader(String hostHeader)
    {
        _hostHeader = hostHeader;
    }

    /* ------------------------------------------------------------ */
    /*
     *
     * @see #setForwarded(boolean)
     */
    public String getForwardedHostHeader()
    {
        return _forwardedHostHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param forwardedHostHeader
     *            The header name for forwarded hosts (default x-forwarded-host)
     * @see #setForwarded(boolean)
     */
    public void setForwardedHostHeader(String forwardedHostHeader)
    {
        _forwardedHostHeader = forwardedHostHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the header name for forwarded server.
     * @see #setForwarded(boolean)
     */
    public String getForwardedServerHeader()
    {
        return _forwardedServerHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param forwardedServerHeader
     *            The header name for forwarded server (default x-forwarded-server)
     * @see #setForwarded(boolean)
     */
    public void setForwardedServerHeader(String forwardedServerHeader)
    {
        _forwardedServerHeader = forwardedServerHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see #setForwarded(boolean)
     */
    public String getForwardedForHeader()
    {
        return _forwardedForHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param forwardedRemoteAddressHeader
     *            The header name for forwarded for (default x-forwarded-for)
     * @see #setForwarded(boolean)
     */
    public void setForwardedForHeader(String forwardedRemoteAddressHeader)
    {
        _forwardedForHeader = forwardedRemoteAddressHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * Get the forwardedProtoHeader.
     *
     * @return the forwardedProtoHeader (default X-Forwarded-For)
     * @see #setForwarded(boolean)
     */
    public String getForwardedProtoHeader()
    {
        return _forwardedProtoHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the forwardedProtoHeader.
     *
     * @param forwardedProtoHeader
     *            the forwardedProtoHeader to set (default X-Forwarded-For)
     * @see #setForwarded(boolean)
     */
    public void setForwardedProtoHeader(String forwardedProtoHeader)
    {
        _forwardedProtoHeader = forwardedProtoHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The header name holding a forwarded cipher suite (default null)
     */
    public String getForwardedCipherSuiteHeader()
    {
        return _forwardedCipherSuiteHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param forwardedCipherSuite
     *            The header name holding a forwarded cipher suite (default null)
     */
    public void setForwardedCipherSuiteHeader(String forwardedCipherSuite)
    {
        _forwardedCipherSuiteHeader = forwardedCipherSuite;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The header name holding a forwarded SSL Session ID (default null)
     */
    public String getForwardedSslSessionIdHeader()
    {
        return _forwardedSslSessionIdHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param forwardedSslSessionId
     *            The header name holding a forwarded SSL Session ID (default null)
     */
    public void setForwardedSslSessionIdHeader(String forwardedSslSessionId)
    {
        _forwardedSslSessionIdHeader = forwardedSslSessionId;
    }

    public int getRequestBufferSize()
    {
        return _buffers.getRequestBufferSize();
    }

    public void setRequestBufferSize(int requestBufferSize)
    {
        _buffers.setRequestBufferSize(requestBufferSize);
    }

    public int getRequestHeaderSize()
    {
        return _buffers.getRequestHeaderSize();
    }

    public void setRequestHeaderSize(int requestHeaderSize)
    {
        _buffers.setRequestHeaderSize(requestHeaderSize);
    }

    public int getResponseBufferSize()
    {
        return _buffers.getResponseBufferSize();
    }

    public void setResponseBufferSize(int responseBufferSize)
    {
        _buffers.setResponseBufferSize(responseBufferSize);
    }

    public int getResponseHeaderSize()
    {
        return _buffers.getResponseHeaderSize();
    }

    public void setResponseHeaderSize(int responseHeaderSize)
    {
        _buffers.setResponseHeaderSize(responseHeaderSize);
    }

    public Type getRequestBufferType()
    {
        return _buffers.getRequestBufferType();
    }

    public Type getRequestHeaderType()
    {
        return _buffers.getRequestHeaderType();
    }

    public Type getResponseBufferType()
    {
        return _buffers.getResponseBufferType();
    }

    public Type getResponseHeaderType()
    {
        return _buffers.getResponseHeaderType();
    }

    public void setRequestBuffers(Buffers requestBuffers)
    {
        _buffers.setRequestBuffers(requestBuffers);
    }

    public void setResponseBuffers(Buffers responseBuffers)
    {
        _buffers.setResponseBuffers(responseBuffers);
    }

    public Buffers getRequestBuffers()
    {
        return _buffers.getRequestBuffers();
    }

    public Buffers getResponseBuffers()
    {
        return _buffers.getResponseBuffers();
    }

    public void setMaxBuffers(int maxBuffers)
    {
        _buffers.setMaxBuffers(maxBuffers);
    }

    public int getMaxBuffers()
    {
        return _buffers.getMaxBuffers();
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return String.format("%s@%s:%d",
                getClass().getSimpleName(),
                getHost()==null?"0.0.0.0":getHost(),
                getLocalPort()<=0?getPort():getLocalPort());
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private class Acceptor implements Runnable
    {
        int _acceptor = 0;

        Acceptor(int id)
        {
            _acceptor = id;
        }

        /* ------------------------------------------------------------ */
        public void run()
        {
            Thread current = Thread.currentThread();
            String name;
            synchronized (AbstractConnector.this)
            {
                if (_acceptorThreads == null)
                    return;

                _acceptorThreads[_acceptor] = current;
                name = _acceptorThreads[_acceptor].getName();
                current.setName(name + " Acceptor" + _acceptor + " " + AbstractConnector.this);
            }
            int old_priority = current.getPriority();

            try
            {
                current.setPriority(old_priority - _acceptorPriorityOffset);
                while (isRunning() && getConnection() != null)
                {
                    try
                    {
                        accept(_acceptor);
                    }
                    catch (EofException e)
                    {
                        LOG.ignore(e);
                    }
                    catch (IOException e)
                    {
                        LOG.ignore(e);
                    }
                    catch (InterruptedException x)
                    {
                        // Connector has been stopped
                        LOG.ignore(x);
                    }
                    catch (Throwable e)
                    {
                        LOG.warn(e);
                    }
                }
            }
            finally
            {
                current.setPriority(old_priority);
                current.setName(name);

                synchronized (AbstractConnector.this)
                {
                    if (_acceptorThreads != null)
                        _acceptorThreads[_acceptor] = null;
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    public String getName()
    {
        if (_name == null)
            _name = (getHost() == null?"0.0.0.0":getHost()) + ":" + (getLocalPort() <= 0?getPort():getLocalPort());
        return _name;
    }

    /* ------------------------------------------------------------ */
    public void setName(String name)
    {
        _name = name;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Get the number of requests handled by this connector since last call of statsReset(). If setStatsOn(false) then this is undefined.
     */
    public int getRequests()
    {
        return (int)_requestStats.getTotal();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the connectionsDurationTotal.
     */
    public long getConnectionsDurationTotal()
    {
        return _connectionDurationStats.getTotal();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Number of connections accepted by the server since statsReset() called. Undefined if setStatsOn(false).
     */
    public int getConnections()
    {
        return (int)_connectionStats.getTotal();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Number of connections currently open that were opened since statsReset() called. Undefined if setStatsOn(false).
     */
    public int getConnectionsOpen()
    {
        return (int)_connectionStats.getCurrent();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Maximum number of connections opened simultaneously since statsReset() called. Undefined if setStatsOn(false).
     */
    public int getConnectionsOpenMax()
    {
        return (int)_connectionStats.getMax();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Mean duration in milliseconds of open connections since statsReset() called. Undefined if setStatsOn(false).
     */
    public double getConnectionsDurationMean()
    {
        return _connectionDurationStats.getMean();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Maximum duration in milliseconds of an open connection since statsReset() called. Undefined if setStatsOn(false).
     */
    public long getConnectionsDurationMax()
    {
        return _connectionDurationStats.getMax();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Standard deviation of duration in milliseconds of open connections since statsReset() called. Undefined if setStatsOn(false).
     */
    public double getConnectionsDurationStdDev()
    {
        return _connectionDurationStats.getStdDev();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Mean number of requests per connection since statsReset() called. Undefined if setStatsOn(false).
     */
    public double getConnectionsRequestsMean()
    {
        return _requestStats.getMean();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Maximum number of requests per connection since statsReset() called. Undefined if setStatsOn(false).
     */
    public int getConnectionsRequestsMax()
    {
        return (int)_requestStats.getMax();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Standard deviation of number of requests per connection since statsReset() called. Undefined if setStatsOn(false).
     */
    public double getConnectionsRequestsStdDev()
    {
        return _requestStats.getStdDev();
    }

    /* ------------------------------------------------------------ */
    /**
     * Reset statistics.
     */
    public void statsReset()
    {
        updateNotEqual(_statsStartedAt,-1,System.currentTimeMillis());

        _requestStats.reset();
        _connectionStats.reset();
        _connectionDurationStats.reset();
    }

    /* ------------------------------------------------------------ */
    public void setStatsOn(boolean on)
    {
        if (on && _statsStartedAt.get() != -1)
            return;

        if (LOG.isDebugEnabled())
            LOG.debug("Statistics on = " + on + " for " + this);

        statsReset();
        _statsStartedAt.set(on?System.currentTimeMillis():-1);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return True if statistics collection is turned on.
     */
    public boolean getStatsOn()
    {
        return _statsStartedAt.get() != -1;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Timestamp stats were started at.
     */
    public long getStatsOnMs()
    {
        long start = _statsStartedAt.get();

        return (start != -1)?(System.currentTimeMillis() - start):0;
    }

    /* ------------------------------------------------------------ */
    protected void connectionOpened(Connection connection)
    {
        if (_statsStartedAt.get() == -1)
            return;

        _connectionStats.increment();
    }

    /* ------------------------------------------------------------ */
    protected void connectionUpgraded(Connection oldConnection, Connection newConnection)
    {
        _requestStats.set((oldConnection instanceof AbstractHttpConnection)?((AbstractHttpConnection)oldConnection).getRequests():0);
    }

    /* ------------------------------------------------------------ */
    protected void connectionClosed(Connection connection)
    {
        connection.onClose();

        if (_statsStartedAt.get() == -1)
            return;

        long duration = System.currentTimeMillis() - connection.getTimeStamp();
        int requests = (connection instanceof AbstractHttpConnection)?((AbstractHttpConnection)connection).getRequests():0;
        _requestStats.set(requests);
        _connectionStats.decrement();
        _connectionDurationStats.set(duration);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the acceptorPriority
     */
    public int getAcceptorPriorityOffset()
    {
        return _acceptorPriorityOffset;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the priority offset of the acceptor threads. The priority is adjusted by this amount (default 0) to either favour the acceptance of new threads and
     * newly active connections or to favour the handling of already dispatched connections.
     *
     * @param offset
     *            the amount to alter the priority of the acceptor threads.
     */
    public void setAcceptorPriorityOffset(int offset)
    {
        _acceptorPriorityOffset = offset;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return True if the the server socket will be opened in SO_REUSEADDR mode.
     */
    public boolean getReuseAddress()
    {
        return _reuseAddress;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param reuseAddress
     *            True if the the server socket will be opened in SO_REUSEADDR mode.
     */
    public void setReuseAddress(boolean reuseAddress)
    {
        _reuseAddress = reuseAddress;
    }

    /* ------------------------------------------------------------ */
    public boolean isLowResources()
    {
        if (_threadPool != null)
            return _threadPool.isLowOnThreads();
        return _server.getThreadPool().isLowOnThreads();
    }

    /* ------------------------------------------------------------ */
    private void updateNotEqual(AtomicLong valueHolder, long compare, long value)
    {
        long oldValue = valueHolder.get();
        while (compare != oldValue)
        {
            if (valueHolder.compareAndSet(oldValue,value))
                break;
            oldValue = valueHolder.get();
        }
    }
}
