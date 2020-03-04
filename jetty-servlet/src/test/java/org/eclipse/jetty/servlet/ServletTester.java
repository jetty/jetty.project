//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.servlet;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServletTester extends ContainerLifeCycle
{
    private static final Logger LOG = LoggerFactory.getLogger(ServletTester.class);

    private final Server _server = new Server();
    private final LocalConnector _connector = new LocalConnector(_server);
    private final ServletContextHandler _context;

    public Server getServer()
    {
        return _server;
    }

    public LocalConnector getConnector()
    {
        return _connector;
    }

    public void setVirtualHosts(String[] vhosts)
    {
        _context.setVirtualHosts(vhosts);
    }

    public void addVirtualHosts(String[] virtualHosts)
    {
        _context.addVirtualHosts(virtualHosts);
    }

    public ServletHolder addServlet(String className, String pathSpec)
    {
        return _context.addServlet(className, pathSpec);
    }

    public ServletHolder addServlet(Class<? extends Servlet> servlet, String pathSpec)
    {
        return _context.addServlet(servlet, pathSpec);
    }

    public void addServlet(ServletHolder servlet, String pathSpec)
    {
        _context.addServlet(servlet, pathSpec);
    }

    public void addFilter(FilterHolder holder, String pathSpec, EnumSet<DispatcherType> dispatches)
    {
        _context.addFilter(holder, pathSpec, dispatches);
    }

    public FilterHolder addFilter(Class<? extends Filter> filterClass, String pathSpec, EnumSet<DispatcherType> dispatches)
    {
        return _context.addFilter(filterClass, pathSpec, dispatches);
    }

    public FilterHolder addFilter(String filterClass, String pathSpec, EnumSet<DispatcherType> dispatches)
    {
        return _context.addFilter(filterClass, pathSpec, dispatches);
    }

    public Object getAttribute(String name)
    {
        return _context.getAttribute(name);
    }

    public Enumeration<String> getAttributeNames()
    {
        return _context.getAttributeNames();
    }

    public Attributes getAttributes()
    {
        return _context.getAttributes();
    }

    public String getContextPath()
    {
        return _context.getContextPath();
    }

    public String getInitParameter(String name)
    {
        return _context.getInitParameter(name);
    }

    public String setInitParameter(String name, String value)
    {
        return _context.setInitParameter(name, value);
    }

    public Enumeration<String> getInitParameterNames()
    {
        return _context.getInitParameterNames();
    }

    public Map<String, String> getInitParams()
    {
        return _context.getInitParams();
    }

    public void removeAttribute(String name)
    {
        _context.removeAttribute(name);
    }

    public void setAttribute(String name, Object value)
    {
        _context.setAttribute(name, value);
    }

    public void setContextPath(String contextPath)
    {
        _context.setContextPath(contextPath);
    }

    public Resource getBaseResource()
    {
        return _context.getBaseResource();
    }

    public String getResourceBase()
    {
        return _context.getResourceBase();
    }

    public void setResourceBase(String resourceBase)
    {
        _context.setResourceBase(resourceBase);
    }

    public ServletTester()
    {
        this("/", ServletContextHandler.SECURITY | ServletContextHandler.SESSIONS);
    }

    public ServletTester(String ctxPath)
    {
        this(ctxPath, ServletContextHandler.SECURITY | ServletContextHandler.SESSIONS);
    }

    public ServletTester(String contextPath, int options)
    {
        _context = new ServletContextHandler(_server, contextPath, options);
        _server.setConnectors(new Connector[]{_connector});
        addBean(_server);
    }

    public ServletContextHandler getContext()
    {
        return _context;
    }

    public String getResponses(String request) throws Exception
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Request: {}", request);
        }
        return _connector.getResponse(request);
    }

    public String getResponses(String request, long idleFor, TimeUnit units) throws Exception
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Request: {}", request);
        }
        return _connector.getResponse(request, idleFor, units);
    }

    public ByteBuffer getResponses(ByteBuffer request) throws Exception
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Request (Buffer): {}", BufferUtil.toUTF8String(request));
        }
        return _connector.getResponse(request);
    }

    public ByteBuffer getResponses(ByteBuffer requestsBuffer, long idleFor, TimeUnit units) throws Exception
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Requests (Buffer): {}", BufferUtil.toUTF8String(requestsBuffer));
        }
        return _connector.getResponse(requestsBuffer, idleFor, units);
    }

    /**
     * Create a port based connector.
     * This methods adds a port connector to the server
     *
     * @param localhost true if connector should use localhost, false for default host behavior.
     * @return A URL to access the server via the connector.
     * @throws Exception on test failure
     */
    public String createConnector(boolean localhost) throws Exception
    {
        ServerConnector connector = new ServerConnector(_server);
        if (localhost)
            connector.setHost("127.0.0.1");
        _server.addConnector(connector);
        if (_server.isStarted())
            connector.start();
        else
            connector.open();

        return "http://" + (localhost ? "127.0.0.1"
                : InetAddress.getLocalHost().getHostAddress()) +
                ":" + connector.getLocalPort();
    }

    public LocalConnector createLocalConnector()
    {
        LocalConnector connector = new LocalConnector(_server);
        _server.addConnector(connector);
        return connector;
    }
}
