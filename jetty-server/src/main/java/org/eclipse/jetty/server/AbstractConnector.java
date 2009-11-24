// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;

import javax.servlet.ServletRequest;

import org.eclipse.jetty.http.HttpBuffers;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpSchemes;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.ThreadPool;


/** Abstract Connector implementation.
 * This abstract implementation of the Connector interface provides:<ul>
 * <li>AbstractLifeCycle implementation</li>
 * <li>Implementations for connector getters and setters</li>
 * <li>Buffer management</li>
 * <li>Socket configuration</li>
 * <li>Base acceptor thread</li>
 * <li>Optional reverse proxy headers checking</li>
 * </ul>
 *
 *
 */
public abstract class AbstractConnector extends HttpBuffers implements Connector
{
    private String _name;

    private Server _server;
    private ThreadPool _threadPool;
    private String _host;
    private int _port=0;
    private String _integralScheme=HttpSchemes.HTTPS;
    private int _integralPort=0;
    private String _confidentialScheme=HttpSchemes.HTTPS;
    private int _confidentialPort=0;
    private int _acceptQueueSize=0;
    private int _acceptors=1;
    private int _acceptorPriorityOffset=0;
    private boolean _useDNS;
    private boolean _forwarded;
    private String _hostHeader;
    private String _forwardedHostHeader = "X-Forwarded-Host";             // default to mod_proxy_http header
    private String _forwardedServerHeader = "X-Forwarded-Server";         // default to mod_proxy_http header
    private String _forwardedForHeader = "X-Forwarded-For";               // default to mod_proxy_http header
    private boolean _reuseAddress=true;

    protected int _maxIdleTime=200000;
    protected int _lowResourceMaxIdleTime=-1;
    protected int _soLingerTime=-1;

    private transient Thread[] _acceptorThread;

    final Object _statsLock = new Object();
    transient long _statsStartedAt=-1;

    // TODO use concurrents for these!
    transient int _requests;
    transient int _connections;                  // total number of connections made to server

    transient int _connectionsOpen;              // number of connections currently open
    transient int _connectionsOpenMin;           // min number of connections open simultaneously
    transient int _connectionsOpenMax;           // max number of connections open simultaneously

    transient long _connectionsDurationMin;      // min duration of a connection
    transient long _connectionsDurationMax;      // max duration of a connection
    transient long _connectionsDurationTotal;    // total duration of all coneection

    transient int _connectionsRequestsMin;       // min requests per connection
    transient int _connectionsRequestsMax;       // max requests per connection


    /* ------------------------------------------------------------------------------- */
    /**
     */
    public AbstractConnector()
    {
    }

    /* ------------------------------------------------------------------------------- */
    public final Buffer newBuffer(int size)
    {
        // TODO remove once no overrides established
        return null;
    }

    /* ------------------------------------------------------------------------------- */
    @Override
    public Buffer newRequestBuffer(int size)
    {
        return new ByteArrayBuffer(size);
    }

    /* ------------------------------------------------------------------------------- */
    @Override
    public Buffer newRequestHeader(int size)
    {
        return new ByteArrayBuffer(size);
    }

    /* ------------------------------------------------------------------------------- */
    @Override
    public Buffer newResponseBuffer(int size)
    {
        return new ByteArrayBuffer(size);
    }

    /* ------------------------------------------------------------------------------- */
    @Override
    public Buffer newResponseHeader(int size)
    {
        return new ByteArrayBuffer(size);
    }

    /* ------------------------------------------------------------------------------- */
    @Override
    protected boolean isRequestHeader(Buffer buffer)
    {
        return true;
    }

    /* ------------------------------------------------------------------------------- */
    @Override
    protected boolean isResponseHeader(Buffer buffer)
    {
        return true;
    }

    /* ------------------------------------------------------------------------------- */
    /*
     */
    public Server getServer()
    {
        return _server;
    }

    /* ------------------------------------------------------------------------------- */
    public void setServer(Server server)
    {
        _server=server;
    }

    /* ------------------------------------------------------------------------------- */
    /*
     * @see org.eclipse.jetty.http.HttpListener#getHttpServer()
     */
    public ThreadPool getThreadPool()
    {
        return _threadPool;
    }

    /* ------------------------------------------------------------------------------- */
    public void setThreadPool(ThreadPool pool)
    {
        _threadPool=pool;
    }

    /* ------------------------------------------------------------------------------- */
    /**
     */
    public void setHost(String host)
    {
        _host=host;
    }

    /* ------------------------------------------------------------------------------- */
    /*
     */
    public String getHost()
    {
        return _host;
    }

    /* ------------------------------------------------------------------------------- */
    /*
     * @see org.eclipse.jetty.server.server.HttpListener#setPort(int)
     */
    public void setPort(int port)
    {
        _port=port;
    }

    /* ------------------------------------------------------------------------------- */
    /*
     * @see org.eclipse.jetty.server.server.HttpListener#getPort()
     */
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
     * Set the maximum Idle time for a connection, which roughly translates
     * to the {@link Socket#setSoTimeout(int)} call, although with NIO
     * implementations other mechanisms may be used to implement the timeout.
     * The max idle time is applied:<ul>
     * <li>When waiting for a new request to be received on a connection</li>
     * <li>When reading the headers and content of a request</li>
     * <li>When writing the headers and content of a response</li>
     * </ul>
     * Jetty interprets this value as the maximum time between some progress being
     * made on the connection. So if a single byte is read or written, then the
     * timeout (if implemented by jetty) is reset.  However, in many instances,
     * the reading/writing is delegated to the JVM, and the semantic is more
     * strictly enforced as the maximum time a single read/write operation can
     * take.  Note, that as Jetty supports writes of memory mapped file buffers,
     * then a write may take many 10s of seconds for large content written to a
     * slow device.
     * <p>
     * Previously, Jetty supported separate idle timeouts and IO operation timeouts,
     * however the expense of changing the value of soTimeout was significant, so
     * these timeouts were merged. With the advent of NIO, it may be possible to
     * again differentiate these values (if there is demand).
     *
     * @param maxIdleTime The maxIdleTime to set.
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
     * @param maxIdleTime The maxIdleTime to set when resources are low.
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
    public final int getLowResourceMaxIdleTime()
    {
        return getLowResourcesMaxIdleTime();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param maxIdleTime The maxIdleTime to set when resources are low.
     * @deprecated
     */
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
     * @param acceptQueueSize The acceptQueueSize to set.
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
     * @param acceptors The number of acceptor threads to set.
     */
    public void setAcceptors(int acceptors)
    {
        _acceptors = acceptors;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param soLingerTime The soLingerTime to set or -1 to disable.
     */
    public void setSoLingerTime(int soLingerTime)
    {
        _soLingerTime = soLingerTime;
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStart() throws Exception
    {
        if (_server==null)
            throw new IllegalStateException("No server");

        // open listener port
        open();

        super.doStart();

        if (_threadPool==null)
            _threadPool=_server.getThreadPool();
        if (_threadPool!=_server.getThreadPool() && (_threadPool instanceof LifeCycle))
            ((LifeCycle)_threadPool).start();

        // Start selector thread
        synchronized(this)
        {
            _acceptorThread=new Thread[getAcceptors()];

            for (int i=0;i<_acceptorThread.length;i++)
            {
                if (!_threadPool.dispatch(new Acceptor(i)))
                {
                    Log.warn("insufficient maxThreads configured for {}",this);
                    break;
                }
            }
        }

        Log.info("Started {}",this);
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStop() throws Exception
    {
        try{close();} catch(IOException e) {Log.warn(e);}

        if (_threadPool==_server.getThreadPool())
            _threadPool=null;
        else if (_threadPool instanceof LifeCycle)
            ((LifeCycle)_threadPool).stop();

        super.doStop();

        Thread[] acceptors=null;
        synchronized(this)
        {
            acceptors=_acceptorThread;
            _acceptorThread=null;
        }
        if (acceptors != null)
        {
            for (int i=0;i<acceptors.length;i++)
            {
                Thread thread=acceptors[i];
                if (thread!=null)
                    thread.interrupt();
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void join() throws InterruptedException
    {
        Thread[] threads=_acceptorThread;
        if (threads!=null)
            for (int i=0;i<threads.length;i++)
                if (threads[i]!=null)
                    threads[i].join();
    }

    /* ------------------------------------------------------------ */
    protected void configure(Socket socket)
        throws IOException
    {
        try
        {
            socket.setTcpNoDelay(true);
            if (_maxIdleTime >= 0)
                socket.setSoTimeout(_maxIdleTime);
            if (_soLingerTime >= 0)
                socket.setSoLinger(true, _soLingerTime/1000);
            else
                socket.setSoLinger(false, 0);
        }
        catch (Exception e)
        {
            Log.ignore(e);
        }
    }


    /* ------------------------------------------------------------ */
    public void customize(EndPoint endpoint, Request request)
        throws IOException
    {
        if (isForwarded())
            checkForwardedHeaders(endpoint, request);
    }

    /* ------------------------------------------------------------ */
    protected void checkForwardedHeaders(EndPoint endpoint, Request request)
        throws IOException
    {
        HttpFields httpFields = request.getConnection().getRequestFields();

        // Retrieving headers from the request
        String forwardedHost = getLeftMostValue(httpFields.getStringField(getForwardedHostHeader()));
        String forwardedServer = getLeftMostValue(httpFields.getStringField(getForwardedServerHeader()));
        String forwardedFor = getLeftMostValue(httpFields.getStringField(getForwardedForHeader()));

        if (_hostHeader!=null)
        {
            // Update host header
            httpFields.put(HttpHeaders.HOST_BUFFER, _hostHeader);
            request.setServerName(null);
            request.setServerPort(-1);
            request.getServerName();
        }
        else if (forwardedHost != null)
        {
            // Update host header
            httpFields.put(HttpHeaders.HOST_BUFFER, forwardedHost);
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
                    Log.ignore(e);
                }
            }

            request.setRemoteHost(inetAddress==null?forwardedFor:inetAddress.getHostName());
        }
    }

    /* ------------------------------------------------------------ */
    protected String getLeftMostValue(String headerValue) {
        if (headerValue == null)
            return null;

        int commaIndex = headerValue.indexOf(',');

        if (commaIndex == -1)
        {
            // Single value
            return headerValue;
        }

        // The left-most value is the farthest downstream client
        return headerValue.substring(0, commaIndex);
    }

    /* ------------------------------------------------------------ */
    public void persist(EndPoint endpoint)
        throws IOException
    {
    }


    /* ------------------------------------------------------------ */
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
     * @see org.eclipse.jetty.server.Connector#isConfidential(org.eclipse.jetty.server.Request)
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
        return false;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param confidentialPort The confidentialPort to set.
     */
    public void setConfidentialPort(int confidentialPort)
    {
        _confidentialPort = confidentialPort;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param confidentialScheme The confidentialScheme to set.
     */
    public void setConfidentialScheme(String confidentialScheme)
    {
        _confidentialScheme = confidentialScheme;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param integralPort The integralPort to set.
     */
    public void setIntegralPort(int integralPort)
    {
        _integralPort = integralPort;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param integralScheme The integralScheme to set.
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
        _useDNS=resolve;
    }

    /* ------------------------------------------------------------ */
    /**
     * Is reverse proxy handling on?
     * @return true if this connector is checking the x-forwarded-for/host/server headers
     */
    public boolean isForwarded()
    {
        return _forwarded;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set reverse proxy handling
     * @param check true if this connector is checking the x-forwarded-for/host/server headers
     */
    public void setForwarded(boolean check)
    {
        if (check)
            Log.debug(this+" is forwarded");
        _forwarded=check;
    }

    /* ------------------------------------------------------------ */
    public String getHostHeader()
    {
        return _hostHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set a forced valued for the host header to control what is returned
     * by {@link ServletRequest#getServerName()} and {@link ServletRequest#getServerPort()}.
     * This value is only used if {@link #isForwarded()} is true.
     * @param hostHeader The value of the host header to force.
     */
    public void setHostHeader(String hostHeader)
    {
        _hostHeader=hostHeader;
    }

    /* ------------------------------------------------------------ */
    public String getForwardedHostHeader()
    {
        return _forwardedHostHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param forwardedHostHeader The header name for forwarded hosts (default x-forwarded-host)
     */
    public void setForwardedHostHeader(String forwardedHostHeader)
    {
        _forwardedHostHeader=forwardedHostHeader;
    }

    /* ------------------------------------------------------------ */
    public String getForwardedServerHeader()
    {
        return _forwardedServerHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param forwardedHostHeader The header name for forwarded server (default x-forwarded-server)
     */
    public void setForwardedServerHeader(String forwardedServerHeader)
    {
        _forwardedServerHeader=forwardedServerHeader;
    }

    /* ------------------------------------------------------------ */
    public String getForwardedForHeader()
    {
        return _forwardedForHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param forwardedHostHeader The header name for forwarded for (default x-forwarded-for)
     */
    public void setForwardedForHeader(String forwardedRemoteAddressHeade)
    {
        _forwardedForHeader=forwardedRemoteAddressHeade;
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        String name = this.getClass().getName();
        int dot = name.lastIndexOf('.');
        if (dot>0)
            name=name.substring(dot+1);

        return name+"@"+(getHost()==null?"0.0.0.0":getHost())+":"+(getLocalPort()<=0?getPort():getLocalPort());
    }


    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private class Acceptor implements Runnable
    {
        int _acceptor=0;

        Acceptor(int id)
        {
            _acceptor=id;
        }

        /* ------------------------------------------------------------ */
        public void run()
        {
            Thread current = Thread.currentThread();
            String name;
            synchronized(AbstractConnector.this)
            {
                if (_acceptorThread==null)
                    return;

                _acceptorThread[_acceptor]=current;
                name =_acceptorThread[_acceptor].getName();
                current.setName(name+" - Acceptor"+_acceptor+" "+AbstractConnector.this);
            }
            int old_priority=current.getPriority();

            try
            {
                current.setPriority(old_priority-_acceptorPriorityOffset);
                while (isRunning() && getConnection()!=null)
                {
                    try
                    {
                        accept(_acceptor);
                    }
                    catch(EofException e)
                    {
                        Log.ignore(e);
                    }
                    catch(IOException e)
                    {
                        Log.ignore(e);
                    }
                    catch (InterruptedException x)
                    {
                        // Connector has been stopped
                        Log.ignore(x);
                    }
                    catch(ThreadDeath e)
                    {
                        throw e;
                    }
                    catch(Throwable e)
                    {
                        Log.warn(e);
                    }
                }
            }
            finally
            {
                current.setPriority(old_priority);
                current.setName(name);
                try
                {
                    if (_acceptor==0)
                        close();
                }
                catch (IOException e)
                {
                    Log.warn(e);
                }

                synchronized(AbstractConnector.this)
                {
                    if (_acceptorThread!=null)
                        _acceptorThread[_acceptor]=null;
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    public String getName()
    {
        if (_name==null)
            _name= (getHost()==null?"0.0.0.0":getHost())+":"+(getLocalPort()<=0?getPort():getLocalPort());
        return _name;
    }

    /* ------------------------------------------------------------ */
    public void setName(String name)
    {
        _name = name;
    }



    /* ------------------------------------------------------------ */
    /**
     * @return Get the number of requests handled by this context
     * since last call of statsReset(). If setStatsOn(false) then this
     * is undefined.
     */
    public int getRequests() {return _requests;}

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the connectionsDurationMin.
     */
    public long getConnectionsDurationMin()
    {
        return _connectionsDurationMin;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the connectionsDurationTotal.
     */
    public long getConnectionsDurationTotal()
    {
        return _connectionsDurationTotal;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the connectionsOpenMin.
     */
    public int getConnectionsOpenMin()
    {
        return _connectionsOpenMin;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the connectionsRequestsMin.
     */
    public int getConnectionsRequestsMin()
    {
        return _connectionsRequestsMin;
    }


    /* ------------------------------------------------------------ */
    /**
     * @return Number of connections accepted by the server since
     * statsReset() called. Undefined if setStatsOn(false).
     */
    public int getConnections() {return _connections;}

    /* ------------------------------------------------------------ */
    /**
     * @return Number of connections currently open that were opened
     * since statsReset() called. Undefined if setStatsOn(false).
     */
    public int getConnectionsOpen() {return _connectionsOpen;}

    /* ------------------------------------------------------------ */
    /**
     * @return Maximum number of connections opened simultaneously
     * since statsReset() called. Undefined if setStatsOn(false).
     */
    public int getConnectionsOpenMax() {return _connectionsOpenMax;}

    /* ------------------------------------------------------------ */
    /**
     * @return Average duration in milliseconds of open connections
     * since statsReset() called. Undefined if setStatsOn(false).
     */
    public long getConnectionsDurationAve() {return _connections==0?0:(_connectionsDurationTotal/_connections);}

    /* ------------------------------------------------------------ */
    /**
     * @return Maximum duration in milliseconds of an open connection
     * since statsReset() called. Undefined if setStatsOn(false).
     */
    public long getConnectionsDurationMax() {return _connectionsDurationMax;}

    /* ------------------------------------------------------------ */
    /**
     * @return Average number of requests per connection
     * since statsReset() called. Undefined if setStatsOn(false).
     */
    public int getConnectionsRequestsAve() {return _connections==0?0:(_requests/_connections);}

    /* ------------------------------------------------------------ */
    /**
     * @return Maximum number of requests per connection
     * since statsReset() called. Undefined if setStatsOn(false).
     */
    public int getConnectionsRequestsMax() {return _connectionsRequestsMax;}



    /* ------------------------------------------------------------ */
    /** Reset statistics.
     */
    public void statsReset()
    {
        _statsStartedAt=_statsStartedAt==-1?-1:System.currentTimeMillis();

        _connections=0;

        _connectionsOpenMin=_connectionsOpen;
        _connectionsOpenMax=_connectionsOpen;
        _connectionsOpen=0;

        _connectionsDurationMin=0;
        _connectionsDurationMax=0;
        _connectionsDurationTotal=0;

        _requests=0;

        _connectionsRequestsMin=0;
        _connectionsRequestsMax=0;
    }

    /* ------------------------------------------------------------ */
    public void setStatsOn(boolean on)
    {
        if (on && _statsStartedAt!=-1)
            return;
        Log.debug("Statistics on = "+on+" for "+this);
        statsReset();
        _statsStartedAt=on?System.currentTimeMillis():-1;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return True if statistics collection is turned on.
     */
    public boolean getStatsOn()
    {
        return _statsStartedAt!=-1;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Timestamp stats were started at.
     */
    public long getStatsOnMs()
    {
        return (_statsStartedAt!=-1)?(System.currentTimeMillis()-_statsStartedAt):0;
    }

    /* ------------------------------------------------------------ */
    protected void connectionOpened(Connection connection)
    {
        if (_statsStartedAt==-1)
            return;
        synchronized(_statsLock)
        {
            _connectionsOpen++;
            if (_connectionsOpen > _connectionsOpenMax)
                _connectionsOpenMax=_connectionsOpen;
        }
    }

    /* ------------------------------------------------------------ */
    protected void connectionUpgraded(Connection oldConnection,Connection newConnection)
    {
        int requests=(oldConnection instanceof HttpConnection)?((HttpConnection)oldConnection).getRequests():0;

        synchronized(_statsLock)
        {
            _requests+=requests;
        }
    }
    
    /* ------------------------------------------------------------ */
    protected void connectionClosed(Connection connection)
    {
        if (_statsStartedAt>=0)
        {
            long duration=System.currentTimeMillis()-connection.getTimeStamp();
            
            int requests=(connection instanceof HttpConnection)?((HttpConnection)connection).getRequests():0;

            synchronized(_statsLock)
            {
                _requests+=requests;
                _connections++;
                _connectionsOpen--;
                _connectionsDurationTotal+=duration;
                if (_connectionsOpen<0)
                    _connectionsOpen=0;
                if (_connectionsOpen<_connectionsOpenMin)
                    _connectionsOpenMin=_connectionsOpen;
                if (_connectionsDurationMin==0 || duration<_connectionsDurationMin)
                    _connectionsDurationMin=duration;
                if (duration>_connectionsDurationMax)
                    _connectionsDurationMax=duration;
                if (_connectionsRequestsMin==0 || requests<_connectionsRequestsMin)
                    _connectionsRequestsMin=requests;
                if (requests>_connectionsRequestsMax)
                    _connectionsRequestsMax=requests;
            }
        }

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
     * Set the priority offset of the acceptor threads. The priority is adjusted by
     * this amount (default 0) to either favour the acceptance of new threads and newly active
     * connections or to favour the handling of already dispatched connections.
     * @param offset the amount to alter the priority of the acceptor threads.
     */
    public void setAcceptorPriorityOffset(int offset)
    {
        _acceptorPriorityOffset=offset;
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
     * @param reuseAddress True if the the server socket will be opened in SO_REUSEADDR mode.
     */
    public void setReuseAddress(boolean reuseAddress)
    {
        _reuseAddress=reuseAddress;
    }

    /* ------------------------------------------------------------ */
    public boolean isLowResources()
    {
        if (_threadPool!=null)
            return _threadPool.isLowOnThreads();
        return _server.getThreadPool().isLowOnThreads();
    }
}
