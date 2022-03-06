//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.servlet;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.descriptor.JspConfigDescriptor;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Attributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A simple implementation of ServletContext that is used when there is no
 * ContextHandler.  This is also used as the base for all other ServletContext
 * implementations.
 *
 * TODO This is not longer needed as requests always have a context
 */
public class StaticContext implements ServletContext
{
    private static final Logger LOG = LoggerFactory.getLogger(StaticContext.class);

    public static final int SERVLET_MAJOR_VERSION = 6;
    public static final int SERVLET_MINOR_VERSION = 0;
    private static final String UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER = "Unimplemented {} - use org.eclipse.jetty.servlet.ServletContextHandler";

    private final Attributes.Lazy _attributes = new Attributes.Lazy();
    private int _effectiveMajorVersion = SERVLET_MAJOR_VERSION;
    private int _effectiveMinorVersion = SERVLET_MINOR_VERSION;

    public Set<Map.Entry<String, Object>> getAttributeEntrySet()
    {
        return _attributes.getAttributeNameSet().stream().map(name -> new Map.Entry<String, Object>()
        {
            @Override
            public String getKey()
            {
                return name;
            }

            @Override
            public Object getValue()
            {
                return _attributes.getAttribute(name);
            }

            @Override
            public Object setValue(Object value)
            {
                return _attributes.setAttribute(name, value);
            }
        }).collect(Collectors.toSet());
    }

    @Override
    public ServletContext getContext(String uripath)
    {
        return null;
    }

    @Override
    public int getMajorVersion()
    {
        return SERVLET_MAJOR_VERSION;
    }

    @Override
    public String getMimeType(String file)
    {
        return null;
    }

    @Override
    public int getMinorVersion()
    {
        return SERVLET_MINOR_VERSION;
    }

    @Override
    public RequestDispatcher getNamedDispatcher(String name)
    {
        return null;
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String uriInContext)
    {
        return null;
    }

    @Override
    public String getRealPath(String path)
    {
        return null;
    }

    @Override
    public URL getResource(String path) throws MalformedURLException
    {
        return null;
    }

    @Override
    public InputStream getResourceAsStream(String path)
    {
        return null;
    }

    @Override
    public Set<String> getResourcePaths(String path)
    {
        return null;
    }

    @Override
    public String getServerInfo()
    {
        return ContextHandler.getServerInfo();
    }

    @Override
    public void log(String msg)
    {
        LOG.info(msg);
    }

    @Override
    public void log(String message, Throwable throwable)
    {
        LOG.warn(message, throwable);
    }

    @Override
    public String getInitParameter(String name)
    {
        return null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Enumeration<String> getInitParameterNames()
    {
        return Collections.enumeration(Collections.EMPTY_LIST);
    }

    @Override
    public String getServletContextName()
    {
        return "No Context";
    }

    @Override
    public String getContextPath()
    {
        return null;
    }

    @Override
    public boolean setInitParameter(String name, String value)
    {
        return false;
    }

    @Override
    public Object getAttribute(String name)
    {
        return _attributes.getAttribute(name);
    }

    @Override
    public Enumeration<String> getAttributeNames()
    {
        return Collections.enumeration(_attributes.getAttributeNameSet());
    }

    @Override
    public void setAttribute(String name, Object object)
    {
        _attributes.setAttribute(name, object);
    }

    @Override
    public void removeAttribute(String name)
    {
        _attributes.removeAttribute(name);
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Class<? extends Filter> filterClass)
    {
        LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "addFilter(String, Class)");
        return null;
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Filter filter)
    {
        LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "addFilter(String, Filter)");
        return null;
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, String className)
    {
        LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "addFilter(String, String)");
        return null;
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, Class<? extends Servlet> servletClass)
    {
        LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "addServlet(String, Class)");
        return null;
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, Servlet servlet)
    {
        LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "addServlet(String, Servlet)");
        return null;
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, String className)
    {
        LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "addServlet(String, String)");
        return null;
    }

    /**
     * @since Servlet 4.0
     */
    @Override
    public ServletRegistration.Dynamic addJspFile(String servletName, String jspFile)
    {
        LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "addJspFile(String, String)");
        return null;
    }

    @Override
    public Set<SessionTrackingMode> getDefaultSessionTrackingModes()
    {
        LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "getDefaultSessionTrackingModes()");
        return null;
    }

    @Override
    public Set<SessionTrackingMode> getEffectiveSessionTrackingModes()
    {
        LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "getEffectiveSessionTrackingModes()");
        return null;
    }

    @Override
    public FilterRegistration getFilterRegistration(String filterName)
    {
        LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "getFilterRegistration(String)");
        return null;
    }

    @Override
    public Map<String, ? extends FilterRegistration> getFilterRegistrations()
    {
        LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "getFilterRegistrations()");
        return null;
    }

    @Override
    public ServletRegistration getServletRegistration(String servletName)
    {
        LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "getServletRegistration(String)");
        return null;
    }

    @Override
    public Map<String, ? extends ServletRegistration> getServletRegistrations()
    {
        LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "getServletRegistrations()");
        return null;
    }

    @Override
    public SessionCookieConfig getSessionCookieConfig()
    {
        LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "getSessionCookieConfig()");
        return null;
    }

    @Override
    public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes)
    {
        LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "setSessionTrackingModes(Set<SessionTrackingMode>)");
    }

    @Override
    public void addListener(String className)
    {
        LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "addListener(String)");
    }

    @Override
    public <T extends EventListener> void addListener(T t)
    {
        LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "addListener(T)");
    }

    @Override
    public void addListener(Class<? extends EventListener> listenerClass)
    {
        LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "addListener(Class)");
    }

    public <T> T createInstance(Class<T> clazz) throws ServletException
    {
        try
        {
            return clazz.getDeclaredConstructor().newInstance();
        }
        catch (Exception e)
        {
            throw new ServletException(e);
        }
    }

    @Override
    public <T extends EventListener> T createListener(Class<T> clazz) throws ServletException
    {
        return createInstance(clazz);
    }

    @Override
    public <T extends Servlet> T createServlet(Class<T> clazz) throws ServletException
    {
        return createInstance(clazz);
    }

    @Override
    public <T extends Filter> T createFilter(Class<T> clazz) throws ServletException
    {
        return createInstance(clazz);
    }

    @Override
    public ClassLoader getClassLoader()
    {
        return ContextHandler.class.getClassLoader();
    }

    @Override
    public int getEffectiveMajorVersion()
    {
        return _effectiveMajorVersion;
    }

    @Override
    public int getEffectiveMinorVersion()
    {
        return _effectiveMinorVersion;
    }

    public void setEffectiveMajorVersion(int v)
    {
        _effectiveMajorVersion = v;
    }

    public void setEffectiveMinorVersion(int v)
    {
        _effectiveMinorVersion = v;
    }

    @Override
    public JspConfigDescriptor getJspConfigDescriptor()
    {
        LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "getJspConfigDescriptor()");
        return null;
    }

    @Override
    public void declareRoles(String... roleNames)
    {
        LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "declareRoles(String...)");
    }

    @Override
    public String getVirtualServerName()
    {
        return null;
    }

    /**
     * @since Servlet 4.0
     */
    @Override
    public int getSessionTimeout()
    {
        LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "getSessionTimeout()");
        return 0;
    }

    /**
     * @since Servlet 4.0
     */
    @Override
    public void setSessionTimeout(int sessionTimeout)
    {
        LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "setSessionTimeout(int)");
    }

    /**
     * @since Servlet 4.0
     */
    @Override
    public String getRequestCharacterEncoding()
    {
        LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "getRequestCharacterEncoding()");
        return null;
    }

    /**
     * @since Servlet 4.0
     */
    @Override
    public void setRequestCharacterEncoding(String encoding)
    {
        LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "setRequestCharacterEncoding(String)");
    }

    /**
     * @since Servlet 4.0
     */
    @Override
    public String getResponseCharacterEncoding()
    {
        LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "getResponseCharacterEncoding()");
        return null;
    }

    /**
     * @since Servlet 4.0
     */
    @Override
    public void setResponseCharacterEncoding(String encoding)
    {
        LOG.warn(UNIMPLEMENTED_USE_SERVLET_CONTEXT_HANDLER, "setResponseCharacterEncoding(String)");
    }
}
