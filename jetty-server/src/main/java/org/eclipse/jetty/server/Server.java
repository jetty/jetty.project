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
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ShutdownThread;
import org.eclipse.jetty.util.thread.ThreadPool;

/* ------------------------------------------------------------ */
/** Jetty HTTP Servlet Server.
 * This class is the main class for the Jetty HTTP Servlet server.
 * It aggregates Connectors (HTTP request receivers) and request Handlers.
 * The server is itself a handler and a ThreadPool.  Connectors use the ThreadPool methods
 * to run jobs that will eventually call the handle method.
 */
@ManagedObject(value="Jetty HTTP Servlet server")
public class Server extends HandlerWrapper implements Attributes
{
    private static final Logger LOG = Log.getLogger(Server.class);

    private static final String __version;
    static
    {
        if (Server.class.getPackage()!=null &&
            "Eclipse.org - Jetty".equals(Server.class.getPackage().getImplementationVendor()) &&
             Server.class.getPackage().getImplementationVersion()!=null)
            __version=Server.class.getPackage().getImplementationVersion();
        else
            __version=System.getProperty("jetty.version","9.x.y.z-SNAPSHOT");
    }

    private final Container _container=new Container();
    private final AttributesMap _attributes = new AttributesMap();
    private final ThreadPool _threadPool;
    private final List<Connector> _connectors = new CopyOnWriteArrayList<>();
    private SessionIdManager _sessionIdManager;
    private boolean _sendServerVersion = true; //send Server: header
    private boolean _sendDateHeader = false; //send Date: header
    private boolean _stopAtShutdown;
    private boolean _dumpAfterStart=false;
    private boolean _dumpBeforeStop=false;


    /* ------------------------------------------------------------ */
    public Server()
    {
        this((ThreadPool)null);
    }

    /* ------------------------------------------------------------ */
    /** Convenience constructor
     * Creates server and a {@link SelectChannelConnector} at the passed port.
     */
    public Server(int port)
    {
        this((ThreadPool)null);
        SelectChannelConnector connector=new SelectChannelConnector(this);
        connector.setPort(port);
        setConnectors(new Connector[]{connector});
    }

    /* ------------------------------------------------------------ */
    /** Convenience constructor
     * Creates server and a {@link SelectChannelConnector} at the passed address.
     */
    public Server(InetSocketAddress addr)
    {
        this((ThreadPool)null);
        SelectChannelConnector connector=new SelectChannelConnector(this);
        connector.setHost(addr.getHostName());
        connector.setPort(addr.getPort());
        setConnectors(new Connector[]{connector});
    }


    /* ------------------------------------------------------------ */
    public Server(ThreadPool pool)
    {
        _threadPool=pool!=null?pool:new QueuedThreadPool();
        addBean(_threadPool);
        setServer(this);
    }


    /* ------------------------------------------------------------ */
    @ManagedAttribute("version of this server")
    public static String getVersion()
    {
        return __version;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the container.
     */
    public Container getContainer()
    {
        return _container;
    }

    /* ------------------------------------------------------------ */
    public boolean getStopAtShutdown()
    {
        return _stopAtShutdown;
    }

    /* ------------------------------------------------------------ */
    public void setStopAtShutdown(boolean stop)
    {
        //if we now want to stop
        if (stop)
        {
            //and we weren't stopping before
            if (!_stopAtShutdown)
            {
                //only register to stop if we're already started (otherwise we'll do it in doStart())
                if (isStarted())
                    ShutdownThread.register(this);
            }
        }
        else
            ShutdownThread.deregister(this);

        _stopAtShutdown=stop;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the connectors.
     */
    @ManagedAttribute("connectors for this server")
    public Connector[] getConnectors()
    {
        List<Connector> connectors = new ArrayList<>(_connectors);
        return connectors.toArray(new Connector[connectors.size()]);
    }

    /* ------------------------------------------------------------ */
    public void addConnector(Connector connector)
    {
        if (_connectors.add(connector))
            _container.update(this, null, connector, "connector");
    }

    /* ------------------------------------------------------------ */
    /**
     * Convenience method which calls {@link #getConnectors()} and {@link #setConnectors(Connector[])} to
     * remove a connector.
     * @param connector The connector to remove.
     */
    public void removeConnector(Connector connector)
    {
        if (_connectors.remove(connector))
            _container.update(this, connector, null, "connector");
    }

    /* ------------------------------------------------------------ */
    /** Set the connectors for this server.
     * Each connector has this server set as it's ThreadPool and its Handler.
     * @param connectors The connectors to set.
     */
    public void setConnectors(Connector[] connectors)
    {
        if (connectors != null)
        {
            for (Connector connector : connectors)
            {
                if (connector.getServer() != this)
                    throw new IllegalArgumentException("Connector " + connector +
                            " cannot be shared among server " + connector.getServer() + " and server " + this);
            }
        }

        Connector[] oldConnectors = getConnectors();
        _container.update(this, oldConnectors, connectors, "connector");
        _connectors.removeAll(Arrays.asList(oldConnectors));
        if (connectors != null)
            _connectors.addAll(Arrays.asList(connectors));
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the threadPool.
     */
    @ManagedAttribute("the server thread pool")
    public ThreadPool getThreadPool()
    {
        return _threadPool;
    }

    /**
     * @return true if {@link #dumpStdErr()} is called after starting
     */
    @ManagedAttribute("dump state to stderr after start")
    public boolean isDumpAfterStart()
    {
        return _dumpAfterStart;
    }

    /**
     * @param dumpAfterStart true if {@link #dumpStdErr()} is called after starting
     */
    public void setDumpAfterStart(boolean dumpAfterStart)
    {
        _dumpAfterStart = dumpAfterStart;
    }

    /**
     * @return true if {@link #dumpStdErr()} is called before stopping
     */
    @ManagedAttribute("dump state to stderr before stop")
    public boolean isDumpBeforeStop()
    {
        return _dumpBeforeStop;
    }

    /**
     * @param dumpBeforeStop true if {@link #dumpStdErr()} is called before stopping
     */
    public void setDumpBeforeStop(boolean dumpBeforeStop)
    {
        _dumpBeforeStop = dumpBeforeStop;
    }



    /* ------------------------------------------------------------ */
    @Override
    protected void doStart() throws Exception
    {
        if (getStopAtShutdown())
            ShutdownThread.register(this);

        LOG.info("jetty-"+__version);
        HttpGenerator.setServerVersion(__version);
        MultiException mex=new MultiException();

        try
        {
            super.doStart();
        }
        catch(Throwable e)
        {
            mex.add(e);
        }

        if (mex.size()==0)
        {
            for (Connector _connector : _connectors)
            {
                try
                {
                    _connector.start();
                }
                catch (Throwable e)
                {
                    mex.add(e);
                }
            }
        }

        if (isDumpAfterStart())
            dumpStdErr();

        mex.ifExceptionThrow();
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStop() throws Exception
    {
        if (isDumpBeforeStop())
            dumpStdErr();

        MultiException mex=new MultiException();

        
        // First close the network connectors to stop accepting new connections
        for (Connector connector : _connectors)
        {
            if (connector instanceof NetworkConnector)
                ((NetworkConnector)connector).close();
        }

        // Then tell the contexts that we are shutting down
        Handler[] contexts = getChildHandlersByClass(Graceful.class);
        for (Handler context : contexts)
        {
            Graceful graceful = (Graceful)context;
            graceful.shutdown();
        }
            
        // Shall we gracefully wait for zero connections?
        long stopTimeout = getStopTimeout();
        if (stopTimeout>0 && LOG.isDebugEnabled()) // TODO disabled unless debg for now
        {
            long stop_by=System.currentTimeMillis()+stopTimeout;
            LOG.info("Graceful shutdown {} by ",this,new Date(stop_by));

            // TODO Need to be able to set the maxIdleTime on each individual connection
            for (Connector connector : _connectors)
            {
                // TODO this is not good enough
                if (connector instanceof AbstractConnector)
                    ((AbstractConnector)connector).setIdleTimeout(1);
            }
            
            for (Connector connector : _connectors)
            {
                while (connector.getStatistics().isRunning() && connector.getStatistics().getConnectionsOpen()>0 && System.currentTimeMillis()<stop_by)
                {
                    System.err.println(((Dumpable)connector).dump());
                    Thread.sleep(100);
                }
            }
        }
    
        // Now stop the connectors (this will close existing connections)
        for (Connector connector : _connectors)
        {
            try
            {
                connector.stop();
            }
            catch (Throwable e)
            {
                mex.add(e);
            }
        }

        // And finall stop everything else
        try
        {
            super.doStop();
        }
        catch (Throwable e)
        {
            mex.add(e);
        }

        mex.ifExceptionThrow();

        if (getStopAtShutdown())
            ShutdownThread.deregister(this);
    }

    /* ------------------------------------------------------------ */
    /* Handle a request from a connection.
     * Called to handle a request on the connection when either the header has been received,
     * or after the entire request has been received (for short requests of known length), or
     * on the dispatch of an async request.
     */
    public void handle(HttpChannel connection) throws IOException, ServletException
    {
        final String target=connection.getRequest().getPathInfo();
        final Request request=connection.getRequest();
        final Response response=connection.getResponse();

        if (LOG.isDebugEnabled())
        {
            LOG.debug("REQUEST "+target+" on "+connection);
            handle(target, request, request, response);
            LOG.debug("RESPONSE "+target+"  "+connection.getResponse().getStatus()+" handled="+request.isHandled());
        }
        else
            handle(target, request, request, response);
    }

    /* ------------------------------------------------------------ */
    /* Handle a request from a connection.
     * Called to handle a request on the connection when either the header has been received,
     * or after the entire request has been received (for short requests of known length), or
     * on the dispatch of an async request.
     */
    public void handleAsync(HttpChannel connection) throws IOException, ServletException
    {
        final HttpChannelState async = connection.getRequest().getAsyncContinuation();
        final HttpChannelState.AsyncEventState state = async.getAsyncEventState();

        final Request baseRequest=connection.getRequest();
        final String path=state.getPath();

        if (path!=null)
        {
            // this is a dispatch with a path
            final String contextPath=state.getServletContext().getContextPath();
            HttpURI uri = new HttpURI(URIUtil.addPaths(contextPath,path));
            baseRequest.setUri(uri);
            baseRequest.setRequestURI(null);
            baseRequest.setPathInfo(baseRequest.getRequestURI());
            if (uri.getQuery()!=null)
                baseRequest.mergeQueryString(uri.getQuery());
        }

        final String target=baseRequest.getPathInfo();
        final HttpServletRequest request=(HttpServletRequest)async.getRequest();
        final HttpServletResponse response=(HttpServletResponse)async.getResponse();

        if (LOG.isDebugEnabled())
        {
            LOG.debug("REQUEST "+target+" on "+connection);
            handle(target, baseRequest, request, response);
            LOG.debug("RESPONSE "+target+"  "+connection.getResponse().getStatus());
        }
        else
            handle(target, baseRequest, request, response);

    }


    /* ------------------------------------------------------------ */
    public void join() throws InterruptedException
    {
        getThreadPool().join();
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     * @return Returns the sessionIdManager.
     */
    public SessionIdManager getSessionIdManager()
    {
        return _sessionIdManager;
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     * @param sessionIdManager The sessionIdManager to set.
     */
    public void setSessionIdManager(SessionIdManager sessionIdManager)
    {
        if (_sessionIdManager!=null)
            removeBean(_sessionIdManager);
        _container.update(this, _sessionIdManager, sessionIdManager, "sessionIdManager",false);
        _sessionIdManager = sessionIdManager;
        if (_sessionIdManager!=null)
            addBean(_sessionIdManager);
    }

    /* ------------------------------------------------------------ */
    public void setSendServerVersion (boolean sendServerVersion)
    {
        _sendServerVersion = sendServerVersion;
    }

    /* ------------------------------------------------------------ */
    @ManagedAttribute("if true, include the server version in HTTP headers")
    public boolean getSendServerVersion()
    {
        return _sendServerVersion;
    }

    /* ------------------------------------------------------------ */
    public void setSendDateHeader(boolean sendDateHeader)
    {
        _sendDateHeader = sendDateHeader;
    }

    /* ------------------------------------------------------------ */
    @ManagedAttribute("if true, include the date in HTTP headers")
    public boolean getSendDateHeader()
    {
        return _sendDateHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * Add an associated bean.
     * The bean will be added to the servers {@link Container}
     * and if it is a {@link LifeCycle} instance, it will be
     * started/stopped along with the Server. Any beans that are also
     * {@link Destroyable}, will be destroyed with the server.
     * @param o the bean object to add
     */
    @Override
    public boolean addBean(Object o)
    {
        if (super.addBean(o))
        {
            _container.addBean(o);
            return true;
        }
        return false;
    }

    /* ------------------------------------------------------------ */
    /**
     * Remove an associated bean.
     */
    @Override
    public boolean removeBean (Object o)
    {
        if (super.removeBean(o))
        {
            _container.removeBean(o);
            return true;
        }
        return false;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.util.AttributesMap#clearAttributes()
     */
    @Override
    public void clearAttributes()
    {
        Enumeration names = _attributes.getAttributeNames();
        while (names.hasMoreElements())
            removeBean(_attributes.getAttribute((String)names.nextElement()));
        _attributes.clearAttributes();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.util.AttributesMap#getAttribute(java.lang.String)
     */
    @Override
    public Object getAttribute(String name)
    {
        return _attributes.getAttribute(name);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.util.AttributesMap#getAttributeNames()
     */
    @Override
    public Enumeration<String> getAttributeNames()
    {
        return AttributesMap.getAttributeNamesCopy(_attributes);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.util.AttributesMap#removeAttribute(java.lang.String)
     */
    @Override
    public void removeAttribute(String name)
    {
        Object bean=_attributes.getAttribute(name);
        if (bean!=null)
            removeBean(bean);
        _attributes.removeAttribute(name);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.util.AttributesMap#setAttribute(java.lang.String, java.lang.Object)
     */
    @Override
    public void setAttribute(String name, Object attribute)
    {
        addBean(attribute);
        _attributes.setAttribute(name, attribute);
    }

    /* ------------------------------------------------------------ */
    /**
     * Set graceful shutdown timeout.  If set, the internal <code>doStop()</code> method will not immediately stop the
     * server. Instead, all {@link Connector}s will be closed so that new connections will not be accepted
     * and all handlers that implement {@link Graceful} will be put into the shutdown mode so that no new requests
     * will be accepted, but existing requests can complete.  The server will then wait the configured timeout
     * before stopping.
     * @param timeoutMS the milliseconds to wait for existing request to complete before stopping the server.
     *
     */
    public void setGracefulShutdown(int timeoutMS)
    {
        // TODO
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return this.getClass().getName()+"@"+Integer.toHexString(hashCode());
    }

    /* ------------------------------------------------------------ */
    @Override
    public void dump(Appendable out,String indent) throws IOException
    {
        dumpThis(out);
        dump(out,indent,TypeUtil.asList(getHandlers()),getBeans(),_connectors);
    }

    /* ------------------------------------------------------------ */
    /* A handler that can be gracefully shutdown.
     * Called by doStop if a {@link #setGracefulShutdown} period is set.
     * TODO: this interface should be part of a restructuring of how we manage the lifecycle of components
     * TODO: it should extend LifeCycle rather than Handler, for example, and should play in concert with
     * TODO: LifeCycle.stop() so that stop==shutdown+await(stopTimeout) to keep the stop semantic.
     */
    public interface Graceful extends Handler
    {
        public void shutdown();
    }

    /* ------------------------------------------------------------ */
    public static void main(String...args) throws Exception
    {
        System.err.println(getVersion());
    }
}
