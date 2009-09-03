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

package org.eclipse.jetty.testing;

import java.net.InetAddress;
import java.util.Enumeration;
import java.util.EventListener;

import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.bio.SocketConnector;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.Attributes;



/* ------------------------------------------------------------ */
/** Testing support for servlets and filters.
 *
 * Allows a programatic setup of a context with servlets and filters for
 * testing.  Raw HTTP requests may be sent to the context and responses received.
 * To avoid handling raw HTTP see {@link org.eclipse.jetty.testing.HttpTester}.
 * <pre>
 *      ServletTester tester=new ServletTester();
 *      tester.setContextPath("/context");
 *      tester.addServlet(TestServlet.class, "/servlet/*");
 *      tester.addServlet("org.eclipse.jetty.servlet.DefaultServlet", "/");
 *      tester.start();
 *      String response = tester.getResponses("GET /context/servlet/info HTTP/1.0\r\n\r\n");
 * </pre>
 *
 * @see org.eclipse.jetty.testing.HttpTester
 *
 *
 */
public class ServletTester
{
    Server _server = new Server();
    LocalConnector _connector = new LocalConnector();
//    Context _context = new Context(Context.SESSIONS|Context.SECURITY);
    //jaspi why security if it is not set up?
    ServletContextHandler _context = new ServletContextHandler(ServletContextHandler.SESSIONS);

    public ServletTester()
    {
        try
        {
            _server.setSendServerVersion(false);
            _server.addConnector(_connector);
            _server.setHandler(_context);
        }
        catch (Error e)
        {
            throw e;
        }
        catch (RuntimeException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /* ------------------------------------------------------------ */
    public void start() throws Exception
    {
        _server.start();
    }

    /* ------------------------------------------------------------ */
    public void stop() throws Exception
    {
        _server.stop();
    }

    /* ------------------------------------------------------------ */
    public ServletContextHandler getContext()
    {
        return _context;
    }

    /* ------------------------------------------------------------ */
    /** Get raw HTTP responses from raw HTTP requests.
     * Multiple requests and responses may be handled, but only if
     * persistent connections conditions apply.
     * @param rawRequests String of raw HTTP requests
     * @return String of raw HTTP responses
     * @throws Exception
     */
    public String getResponses(String rawRequests) throws Exception
    {
        return _connector.getResponses(rawRequests);
    }

    /* ------------------------------------------------------------ */
    /** Get raw HTTP responses from raw HTTP requests.
     * Multiple requests and responses may be handled, but only if
     * persistent connections conditions apply.
     * @param rawRequests String of raw HTTP requests
     * @param connector The connector to handle the responses
     * @return String of raw HTTP responses
     * @throws Exception
     */
    public String getResponses(String rawRequests, LocalConnector connector) throws Exception
    {
        return connector.getResponses(rawRequests);
    }

    /* ------------------------------------------------------------ */
    /** Get raw HTTP responses from raw HTTP requests.
     * Multiple requests and responses may be handled, but only if
     * persistent connections conditions apply.
     * @param rawRequests String of raw HTTP requests
     * @return String of raw HTTP responses
     * @throws Exception
     */
    public ByteArrayBuffer getResponses(ByteArrayBuffer rawRequests) throws Exception
    {
        return _connector.getResponses(rawRequests,false);
    }

    /* ------------------------------------------------------------ */
    /** Create a Socket connector.
     * This methods adds a socket connector to the server
     * @param locahost if true, only listen on local host, else listen on all interfaces.
     * @return A URL to access the server via the socket connector.
     * @throws Exception
     */
    public String createSocketConnector(boolean localhost)
    throws Exception
    {
        synchronized (this)
        {
            SocketConnector connector = new SocketConnector();
            if (localhost)
                connector.setHost("127.0.0.1");
            _server.addConnector(connector);
            if (_server.isStarted())
                connector.start();
            else
                connector.open();

            return "http://127.0.0.1:"+connector.getLocalPort();
        }
    }

    /* ------------------------------------------------------------ */
    /** Create a SelectChannel connector.
     * This methods adds a select channel connector to the server
     * @return A URL to access the server via the connector.
     * @throws Exception
     */
    public String createChannelConnector(boolean localhost)
    throws Exception
    {
        synchronized (this)
        {
            SelectChannelConnector connector = new SelectChannelConnector();
            if (localhost)
                connector.setHost("127.0.0.1");
            _server.addConnector(connector);
            if (_server.isStarted())
                connector.start();
            else
                connector.open();

            return "http://"+(localhost?"127.0.0.1":
                InetAddress.getLocalHost().getHostAddress()
            )+":"+connector.getLocalPort();
        }
    }

    /* ------------------------------------------------------------ */
    /** Create a local connector.
     * This methods adds a local connector to the server
     * @return The LocalConnector object
     * @throws Exception
     */
    public LocalConnector createLocalConnector()
    throws Exception
    {
        synchronized (this)
        {
            LocalConnector connector = new LocalConnector();
            _server.addConnector(connector);

            if (_server.isStarted())
                connector.start();

            return connector;
        }
   }

    /* ------------------------------------------------------------ */
    /**
     * @param listener
     * @see org.eclipse.jetty.handler.ContextHandler#addEventListener(java.util.EventListener)
     */
    public void addEventListener(EventListener listener)
    {
        _context.addEventListener(listener);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param filterClass
     * @param pathSpec
     * @param dispatches
     * @return
     * @see org.eclipse.jetty.servlet.Scope#addFilter(java.lang.Class, java.lang.String, int)
     */
    public FilterHolder addFilter(Class filterClass, String pathSpec, int dispatches)
    {
        return _context.addFilter(filterClass,pathSpec,dispatches);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param filterClass
     * @param pathSpec
     * @param dispatches
     * @return
     * @see org.eclipse.jetty.servlet.Scope#addFilter(java.lang.String, java.lang.String, int)
     */
    public FilterHolder addFilter(String filterClass, String pathSpec, int dispatches)
    {
        return _context.addFilter(filterClass,pathSpec,dispatches);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param servlet
     * @param pathSpec
     * @return
     * @see org.eclipse.jetty.servlet.Scope#addServlet(java.lang.Class, java.lang.String)
     */
    public ServletHolder addServlet(Class servlet, String pathSpec)
    {
        return _context.addServlet(servlet,pathSpec);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param className
     * @param pathSpec
     * @return
     * @see org.eclipse.jetty.servlet.Scope#addServlet(java.lang.String, java.lang.String)
     */
    public ServletHolder addServlet(String className, String pathSpec)
    {
        return _context.addServlet(className,pathSpec);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param name
     * @return
     * @see org.eclipse.jetty.handler.ContextHandler#getAttribute(java.lang.String)
     */
    public Object getAttribute(String name)
    {
        return _context.getAttribute(name);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return
     * @see org.eclipse.jetty.handler.ContextHandler#getAttributeNames()
     */
    public Enumeration getAttributeNames()
    {
        return _context.getAttributeNames();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return
     * @see org.eclipse.jetty.handler.ContextHandler#getAttributes()
     */
    public Attributes getAttributes()
    {
        return _context.getAttributes();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return
     * @see org.eclipse.jetty.handler.ContextHandler#getResourceBase()
     */
    public String getResourceBase()
    {
        return _context.getResourceBase();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param name
     * @param value
     * @see org.eclipse.jetty.handler.ContextHandler#setAttribute(java.lang.String, java.lang.Object)
     */
    public void setAttribute(String name, Object value)
    {
        _context.setAttribute(name,value);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param classLoader
     * @see org.eclipse.jetty.handler.ContextHandler#setClassLoader(java.lang.ClassLoader)
     */
    public void setClassLoader(ClassLoader classLoader)
    {
        _context.setClassLoader(classLoader);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param contextPath
     * @see org.eclipse.jetty.handler.ContextHandler#setContextPath(java.lang.String)
     */
    public void setContextPath(String contextPath)
    {
        _context.setContextPath(contextPath);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param eventListeners
     * @see org.eclipse.jetty.handler.ContextHandler#setEventListeners(java.util.EventListener[])
     */
    public void setEventListeners(EventListener[] eventListeners)
    {
        _context.setEventListeners(eventListeners);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param resourceBase
     * @see org.eclipse.jetty.handler.ContextHandler#setResourceBase(java.lang.String)
     */
    public void setResourceBase(String resourceBase)
    {
        _context.setResourceBase(resourceBase);
    }

}
