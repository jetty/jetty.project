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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.bio.SocketConnector;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
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
    private static final ShutdownHookThread hookThread = new ShutdownHookThread();
    private static final String _version;
    static
    {
        if (Server.class.getPackage()!=null && Server.class.getPackage().getImplementationVersion()!=null)
            _version=Server.class.getPackage().getImplementationVersion();
        else
            _version=System.getProperty("jetty.version","7.0.2-SNAPSHOT");
    }
    private final Container _container=new Container();
    private final AttributesMap _attributes = new AttributesMap();
    private final List<Object> _dependentBeans=new ArrayList<Object>();
    private ThreadPool _threadPool;
    private Connector[] _connectors;
    private SessionIdManager _sessionIdManager;
    private boolean _sendServerVersion = true; //send Server: header
    private boolean _sendDateHeader = false; //send Date: header
    private int _graceful=0;
    private boolean _stopAtShutdown;
    
    /* ------------------------------------------------------------ */
    public Server()
    {
        setServer(this); 
    }
    
    /* ------------------------------------------------------------ */
    /** Convenience constructor
     * Creates server and a {@link SocketConnector} at the passed port.
     */
    public Server(int port)
    {
        setServer(this);

        Connector connector=new SelectChannelConnector();
        connector.setPort(port);
        setConnectors(new Connector[]{connector});
    }


    /* ------------------------------------------------------------ */
    public static String getVersion()
    {
        return _version;
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
        _stopAtShutdown=stop;
        if (stop)
            hookThread.add(this);
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
        _container.update(this,_threadPool,threadPool, "threadpool",true);
        _threadPool = threadPool;
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStart() throws Exception
    {
        if (getStopAtShutdown())
            hookThread.add(this);
        
        Log.info("jetty-"+_version);
        HttpGenerator.setServerVersion(_version);
        MultiException mex=new MultiException();

        Iterator itor = _dependentBeans.iterator();
        while (itor.hasNext())
        {   
            try
            {
                Object o=itor.next();
                if (o instanceof LifeCycle)
                    ((LifeCycle)o).start(); 
            }
            catch (Throwable e) {mex.add(e);}
        }
        
        if (_threadPool==null)
        {
            QueuedThreadPool tp=new QueuedThreadPool();
            setThreadPool(tp);
        }
        
        if (_sessionIdManager!=null)
            _sessionIdManager.start();
        
        try
        {
            if (_threadPool instanceof LifeCycle)
                ((LifeCycle)_threadPool).start();
        } 
        catch(Throwable e) { mex.add(e);}
        
        try 
        { 
            super.doStart(); 
        } 
        catch(Throwable e) 
        { 
            Log.warn("Error starting handlers",e);
        }
        
        if (_connectors!=null)
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
        if (Log.isDebugEnabled())
            Log.debug(dump());
        mex.ifExceptionThrow();
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStop() throws Exception
    {
        MultiException mex=new MultiException();
        
        if (_graceful>0)
        {
            if (_connectors!=null)
            {
                for (int i=_connectors.length;i-->0;)
                {
                    Log.info("Graceful shutdown {}",_connectors[i]);
                    try{_connectors[i].close();}catch(Throwable e){mex.add(e);}
                }
            }
            
            Handler[] contexts = getChildHandlersByClass(Graceful.class);
            for (int c=0;c<contexts.length;c++)
            {
                Graceful context=(Graceful)contexts[c];
                Log.info("Graceful shutdown {}",context);
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
        
        if (_sessionIdManager!=null)
            _sessionIdManager.stop();
        
        try
        {
            if (_threadPool instanceof LifeCycle)
                ((LifeCycle)_threadPool).stop();
        }
        catch(Throwable e){mex.add(e);}
        
        if (!_dependentBeans.isEmpty())
        {
            ListIterator itor = _dependentBeans.listIterator(_dependentBeans.size());
            while (itor.hasPrevious())
            {
                try
                {
                    Object o =itor.previous();
                    if (o instanceof LifeCycle)
                        ((LifeCycle)o).stop(); 
                }
                catch (Throwable e) {mex.add(e);}
            }
        }
       
        mex.ifExceptionThrow();
        hookThread.remove(this);
    }

    /* ------------------------------------------------------------ */
    /* Handle a request from a connection.
     * Called to handle a request on the connection when either the header has been received,
     * or after the entire request has been received (for short requests of known length), or
     * on the dispatch of an async request.
     */
    public void handle(HttpConnection connection) throws IOException, ServletException
    {
        final String target=connection.getRequest().getPathInfo();
        final Request request=connection.getRequest();
        final Response response=connection.getResponse();
        
        if (Log.isDebugEnabled())
        {
            Log.debug("REQUEST "+target+" on "+connection);
            handle(target, request, request, response);
            Log.debug("RESPONSE "+target+"  "+connection.getResponse().getStatus());
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
    public void handleAsync(HttpConnection connection) throws IOException, ServletException
    {
        final AsyncContinuation async = connection.getRequest().getAsyncContinuation();
        final AsyncContinuation.AsyncEventState state = async.getAsyncEventState();

        final Request baseRequest=connection.getRequest();
        final String path=state.getPath();
        if (path!=null)
        {
            // this is a dispatch with a path
            baseRequest.setAttribute(AsyncContext.ASYNC_REQUEST_URI,baseRequest.getRequestURI());
            baseRequest.setAttribute(AsyncContext.ASYNC_QUERY_STRING,baseRequest.getQueryString());
            
            baseRequest.setAttribute(AsyncContext.ASYNC_CONTEXT_PATH,state.getSuspendedContext().getContextPath());

            final String contextPath=state.getServletContext().getContextPath();
            HttpURI uri = new HttpURI(URIUtil.addPaths(contextPath,path));
            baseRequest.setUri(uri);
            baseRequest.setRequestURI(null);
            baseRequest.setPathInfo(baseRequest.getRequestURI());
            baseRequest.setQueryString(uri.getQuery());            
        }
        
        final String target=baseRequest.getPathInfo();
        final HttpServletRequest request=(HttpServletRequest)async.getRequest();
        final HttpServletResponse response=(HttpServletResponse)async.getResponse();

        if (Log.isDebugEnabled())
        {
            Log.debug("REQUEST "+target+" on "+connection);
            handle(target, baseRequest, request, response);
            Log.debug("RESPONSE "+target+"  "+connection.getResponse().getStatus());
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
        _container.update(this,_sessionIdManager,sessionIdManager, "sessionIdManager",true);
        _sessionIdManager = sessionIdManager;
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
     * Add a LifeCycle object to be started/stopped
     * along with the Server.
     * @deprecated Use {@link #addBean(LifeCycle)}
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
     * started/stopped along with the Server.
     * @param c
     */
    public void addBean(Object o)
    {
        if (o == null)
            return;
        
        if (!_dependentBeans.contains(o)) 
        {
            _dependentBeans.add(o);
            _container.addBean(o);
        }
        
        try
        {
            if (isStarted() && o instanceof LifeCycle)
                ((LifeCycle)o).start();
        }
        catch (Exception e)
        {
            throw new RuntimeException (e);
        }
    }

    /* ------------------------------------------------------------ */
    /** Get dependent beans of a specific class
     * @see #addBean(Object)
     * @param clazz
     * @return List of beans.
     */
    public <T> List<T> getBeans(Class<T> clazz)
    {
        ArrayList<T> beans = new ArrayList<T>();
        Iterator<?> iter = _dependentBeans.iterator();
        while (iter.hasNext())
        {
            Object o = iter.next();
            if (clazz.isInstance(o))
                beans.add((T)o);
        }
        return beans;
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

    /**
     * Remove an associated bean.
     */
    public void removeBean (Object o)
    {
        if (o == null)
            return;
        _dependentBeans.remove(o);
        _container.removeBean(o);
    }
    
 
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     * ShutdownHook thread for stopping all servers.
     * 
     * Thread is hooked first time list of servers is changed.
     */
    private static class ShutdownHookThread extends Thread
    {
        private boolean _hooked = false;
        private final Set<Server> _servers = new CopyOnWriteArraySet<Server>();

        /**
         * Hooks this thread for shutdown.
         * 
         * @see java.lang.Runtime#addShutdownHook(java.lang.Thread)
         */
        private void createShutdownHook()
        {
            if (!_hooked)
            {
                try
                {
                    Method shutdownHook = java.lang.Runtime.class.getMethod("addShutdownHook", Thread.class);
                    shutdownHook.invoke(Runtime.getRuntime(), this);
                    _hooked = true;
                }
                catch (Exception e)
                {
                    if (Log.isDebugEnabled())
                        Log.debug("No shutdown hook in JVM ", e);
                }
            }
        }

        /**
         * Add Server to servers list.
         */
        public void add(Server server)
        {
            _servers.add(server);
            if (server.getStopAtShutdown())
                createShutdownHook();
        }

        /**
         * Contains Server in servers list?
         */
        public boolean contains(Server server)
        {
            return _servers.contains(server);
        }

        public Iterable<Server> getServers()
        {
            return _servers;
        }
        
        /**
         * Clear list of Servers.
         */
        public void clear()
        {
            _servers.clear();
        }

        /**
         * Remove Server from list.
         */
        public boolean remove(Server server)
        {
            createShutdownHook();
            return _servers.remove(server);
        }

        /**
         * Stop all Servers in list.
         */
        @Override
        public void run()
        {
            setName("Shutdown");
            Log.info("Shutdown hook executing");
            for (Server svr : _servers)
            {
                if (svr == null || !svr.getStopAtShutdown() || !svr.isRunning())
                    continue;
                try
                {
                    svr.stop();
                }
                catch (Exception e)
                {
                    Log.warn(e);
                }
                Log.info("Shutdown hook complete");

                // Try to avoid JVM crash
                try
                {
                    Thread.sleep(1000);
                }
                catch (Exception e)
                {
                    Log.warn(e);
                }
            }
        }
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
     * Set graceful shutdown timeout.  If set, the {@link #doStop()} method will not immediately stop the 
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
    
    @Override
    public String toString()
    {
        return this.getClass().getName()+"@"+Integer.toHexString(hashCode());
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
    public static void main(String[] args)
    {
        System.err.println(getVersion());
    }
}
