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
import java.net.InetSocketAddress;
import java.util.Enumeration;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.Destroyable;
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
 *
 *  @org.apache.xbean.XBean  description="Creates an embedded Jetty web server"
 */
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
            __version=System.getProperty("jetty.version","8.y.z-SNAPSHOT");
    }

    private final Container _container=new Container();
    private final AttributesMap _attributes = new AttributesMap();
    private ThreadPool _threadPool;
    private Connector[] _connectors;
    private SessionIdManager _sessionIdManager;
    private boolean _sendServerVersion = true; //send Server: header
    private boolean _sendDateHeader = false; //send Date: header
    private int _graceful=0;
    private boolean _stopAtShutdown;
    private boolean _dumpAfterStart=false;
    private boolean _dumpBeforeStop=false;
    private boolean _uncheckedPrintWriter=false;


    /* ------------------------------------------------------------ */
    public Server()
    {
        setServer(this);
    }

    /* ------------------------------------------------------------ */
    /** Convenience constructor
     * Creates server and a {@link SelectChannelConnector} at the passed port.
     */
    public Server(int port)
    {
        setServer(this);

        Connector connector=new SelectChannelConnector();
        connector.setPort(port);
        setConnectors(new Connector[]{connector});
    }

    /* ------------------------------------------------------------ */
    /** Convenience constructor
     * Creates server and a {@link SelectChannelConnector} at the passed address.
     */
    public Server(InetSocketAddress addr)
    {
        setServer(this);

        Connector connector=new SelectChannelConnector();
        connector.setHost(addr.getHostName());
        connector.setPort(addr.getPort());
        setConnectors(new Connector[]{connector});
    }


    /* ------------------------------------------------------------ */
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
    public Connector[] getConnectors()
    {
        return _connectors;
    }

    /* ------------------------------------------------------------ */
    public void addConnector(Connector connector)
    {
        setConnectors((Connector[])LazyList.addToArray(getConnectors(), connector, Connector.class));
    }

    /* ------------------------------------------------------------ */
    /**
     * Conveniance method which calls {@link #getConnectors()} and {@link #setConnectors(Connector[])} to
     * remove a connector.
     * @param connector The connector to remove.
     */
    public void removeConnector(Connector connector) {
        setConnectors((Connector[])LazyList.removeFromArray (getConnectors(), connector));
    }

    /* ------------------------------------------------------------ */
    /** Set the connectors for this server.
     * Each connector has this server set as it's ThreadPool and its Handler.
     * @param connectors The connectors to set.
     */
    public void setConnectors(Connector[] connectors)
    {
        if (connectors!=null)
        {
            for (int i=0;i<connectors.length;i++)
                connectors[i].setServer(this);
        }

        _container.update(this, _connectors, connectors, "connector");
        _connectors = connectors;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the threadPool.
     */
    public ThreadPool getThreadPool()
    {
        return _threadPool;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param threadPool The threadPool to set.
     */
    public void setThreadPool(ThreadPool threadPool)
    {
        if (_threadPool!=null)
            removeBean(_threadPool);
        _container.update(this, _threadPool, threadPool, "threadpool",false);
        _threadPool = threadPool;
        if (_threadPool!=null)
            addBean(_threadPool);
    }

    /**
     * @return true if {@link #dumpStdErr()} is called after starting
     */
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
        {
            ShutdownThread.register(this);    
        }
        
        ShutdownMonitor.getInstance().start(); // initialize

        LOG.info("jetty-"+__version);
        HttpGenerator.setServerVersion(__version);
        
        MultiException mex=new MultiException();

        if (_threadPool==null)
            setThreadPool(new QueuedThreadPool());

        try
        {
            super.doStart();
        }
        catch(Throwable e)
        {
            mex.add(e);
        }

        if (_connectors!=null && mex.size()==0)
        {
            for (int i=0;i<_connectors.length;i++)
            {
                try{_connectors[i].start();}
                catch(Throwable e)
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

        if (_graceful>0)
        {
            if (_connectors!=null)
            {
                for (int i=_connectors.length;i-->0;)
                {
                    LOG.info("Graceful shutdown {}",_connectors[i]);
                    try{_connectors[i].close();}catch(Throwable e){mex.add(e);}
                }
            }

            Handler[] contexts = getChildHandlersByClass(Graceful.class);
            for (int c=0;c<contexts.length;c++)
            {
                Graceful context=(Graceful)contexts[c];
                LOG.info("Graceful shutdown {}",context);
                context.setShutdown(true);
            }
            Thread.sleep(_graceful);
        }

        if (_connectors!=null)
        {
            for (int i=_connectors.length;i-->0;)
                try{_connectors[i].stop();}catch(Throwable e){mex.add(e);}
        }

        try {super.doStop(); } catch(Throwable e) { mex.add(e);}

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
    public void handle(AbstractHttpConnection connection) throws IOException, ServletException
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
    public void handleAsync(AbstractHttpConnection connection) throws IOException, ServletException
    {
        final AsyncContinuation async = connection.getRequest().getAsyncContinuation();
        final AsyncContinuation.AsyncEventState state = async.getAsyncEventState();

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
                baseRequest.mergeQueryString(uri.getQuery()); //we have to assume dispatch path and query are UTF8
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
    public boolean getSendServerVersion()
    {
        return _sendServerVersion;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param sendDateHeader
     */
    public void setSendDateHeader(boolean sendDateHeader)
    {
        _sendDateHeader = sendDateHeader;
    }

    /* ------------------------------------------------------------ */
    public boolean getSendDateHeader()
    {
        return _sendDateHeader;
    }

    /* ------------------------------------------------------------ */
    /** 
     */
    @Deprecated
    public int getMaxCookieVersion()
    {
        return 1;
    }

    /* ------------------------------------------------------------ */
    /** 
     */
    @Deprecated
    public void setMaxCookieVersion(int maxCookieVersion)
    {
    }

    /* ------------------------------------------------------------ */
    /**
     * Add a LifeCycle object to be started/stopped
     * along with the Server.
     * @deprecated Use {@link #addBean(Object)}
     * @param c
     */
    @Deprecated
    public void addLifeCycle (LifeCycle c)
    {
        addBean(c);
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

    /**
     * Remove a LifeCycle object to be started/stopped
     * along with the Server
     * @deprecated Use {@link #removeBean(Object)}
     */
    @Deprecated
    public void removeLifeCycle (LifeCycle c)
    {
        removeBean(c);
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
    public void clearAttributes()
    {
        _attributes.clearAttributes();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.util.AttributesMap#getAttribute(java.lang.String)
     */
    public Object getAttribute(String name)
    {
        return _attributes.getAttribute(name);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.util.AttributesMap#getAttributeNames()
     */
    public Enumeration getAttributeNames()
    {
        return AttributesMap.getAttributeNamesCopy(_attributes);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.util.AttributesMap#removeAttribute(java.lang.String)
     */
    public void removeAttribute(String name)
    {
        _attributes.removeAttribute(name);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.util.AttributesMap#setAttribute(java.lang.String, java.lang.Object)
     */
    public void setAttribute(String name, Object attribute)
    {
        _attributes.setAttribute(name, attribute);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the graceful
     */
    public int getGracefulShutdown()
    {
        return _graceful;
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
        _graceful=timeoutMS;
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
        dump(out,indent,TypeUtil.asList(getHandlers()),getBeans(),TypeUtil.asList(_connectors));
    }


    /* ------------------------------------------------------------ */
    public boolean isUncheckedPrintWriter()
    {
        return _uncheckedPrintWriter;
    }

    /* ------------------------------------------------------------ */
    public void setUncheckedPrintWriter(boolean unchecked)
    {
        _uncheckedPrintWriter=unchecked;
    }


    /* ------------------------------------------------------------ */
    /* A handler that can be gracefully shutdown.
     * Called by doStop if a {@link #setGracefulShutdown} period is set.
     * TODO move this somewhere better
     */
    public interface Graceful extends Handler
    {
        public void setShutdown(boolean shutdown);
    }

    /* ------------------------------------------------------------ */
    public static void main(String...args) throws Exception
    {
        System.err.println(getVersion());
    }
}
