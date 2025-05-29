//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.Map;
import java.util.Objects;
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
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link ServletContext} used for cross context dispatch, which wraps an arbitrary {@link ContextHandler}
 */
class CrossContextServletContext implements ServletContext
{
    private static final Logger LOG = LoggerFactory.getLogger(CrossContextServletContext.class);

    private final ServletContextHandler _servletContextHandler;
    private final ContextHandler.ScopedContext _targetContext;

    protected CrossContextServletContext(ServletContextHandler servletContextHandler, ContextHandler.ScopedContext targetContext)
    {
        _servletContextHandler = servletContextHandler;
        _targetContext = Objects.requireNonNull(targetContext);
    }

    @Override
    public String getContextPath()
    {
        return _targetContext.getContextPath();
    }

    @Override
    public ServletContext getContext(String uripath)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getMajorVersion()
    {
        return _servletContextHandler.getServletContext().getMajorVersion();
    }

    @Override
    public int getMinorVersion()
    {
        return _servletContextHandler.getServletContext().getMinorVersion();
    }

    @Override
    public int getEffectiveMajorVersion()
    {
        return _servletContextHandler.getServletContext().getEffectiveMajorVersion();
    }

    @Override
    public int getEffectiveMinorVersion()
    {
        return _servletContextHandler.getServletContext().getEffectiveMajorVersion();
    }

    @Override
    public String getMimeType(String file)
    {
        return _targetContext.getMimeTypes().getMimeByExtension(file);
    }

    @Override
    public Set<String> getResourcePaths(String path)
    {
        Resource resource = _targetContext.getBaseResource().resolve(path);
        if (resource != null && resource.isDirectory())
            return resource.list().stream().map(Resource::getPath).map(Path::getFileName).map(Path::toString).collect(Collectors.toSet());
        return null;
    }

    @Override
    public URL getResource(String path) throws MalformedURLException
    {
        try
        {
            return _targetContext.getBaseResource().resolve(path).getURI().toURL();
        }
        catch (MalformedURLException e)
        {
            throw e;
        }
        catch (Throwable e)
        {
            // catch IOException, RuntimeException, and things like java.nio.fileInvalidPathException here.
            throw (MalformedURLException)new MalformedURLException(path).initCause(e);
        }
    }

    @Override
    public InputStream getResourceAsStream(String path)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String uriInContext)
    {
        // uriInContext is encoded, potentially with query.
        if (uriInContext == null)
            return null;

        if (!uriInContext.startsWith("/"))
            return null;

        try
        {
            String contextPath = getContextPath();
            // uriInContext is canonical by HttpURI.
            HttpURI.Mutable uri = HttpURI.build(uriInContext);
            String encodedPathInContext = uri.getCanonicalPath();
            if (StringUtil.isEmpty(encodedPathInContext))
                return null;

            if (!StringUtil.isEmpty(contextPath) && !contextPath.equals("/"))
            {
                uri.path(URIUtil.addPaths(contextPath, uri.getPath()));
                encodedPathInContext = uri.getCanonicalPath().substring(contextPath.length());
            }
            return new CrossContextDispatcher(this, uri, URIUtil.decodePath(encodedPathInContext));
        }
        catch (Exception e)
        {
            LOG.trace("IGNORED", e);
        }
        return null;
    }

    @Override
    public RequestDispatcher getNamedDispatcher(String name)
    {
        throw new UnsupportedOperationException();
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
    public String getRealPath(String path)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getServerInfo()
    {
        return _servletContextHandler.getServer().getServerInfo();
    }

    @Override
    public String getInitParameter(String name)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Enumeration<String> getInitParameterNames()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean setInitParameter(String name, String value)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object getAttribute(String name)
    {
        return _targetContext.getAttribute(name);
    }

    @Override
    public Enumeration<String> getAttributeNames()
    {
        return Collections.enumeration(_targetContext.getAttributeNameSet());
    }

    @Override
    public void setAttribute(String name, Object object)
    {
        _targetContext.setAttribute(name, object);
    }

    @Override
    public void removeAttribute(String name)
    {
        _targetContext.removeAttribute(name);
    }

    @Override
    public String getServletContextName()
    {
        return _targetContext.getContextHandler().getDisplayName();
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, String className)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, Servlet servlet)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, Class<? extends Servlet> servletClass)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public ServletRegistration.Dynamic addJspFile(String servletName, String jspFile)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T extends Servlet> T createServlet(Class<T> clazz) throws ServletException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public ServletRegistration getServletRegistration(String servletName)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, ? extends ServletRegistration> getServletRegistrations()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, String className)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Filter filter)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Class<? extends Filter> filterClass)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T extends Filter> T createFilter(Class<T> clazz) throws ServletException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public FilterRegistration getFilterRegistration(String filterName)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, ? extends FilterRegistration> getFilterRegistrations()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public SessionCookieConfig getSessionCookieConfig()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<SessionTrackingMode> getDefaultSessionTrackingModes()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<SessionTrackingMode> getEffectiveSessionTrackingModes()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addListener(String className)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T extends EventListener> void addListener(T t)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addListener(Class<? extends EventListener> listenerClass)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T extends EventListener> T createListener(Class<T> clazz) throws ServletException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public JspConfigDescriptor getJspConfigDescriptor()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public ClassLoader getClassLoader()
    {
        return _targetContext.getClassLoader();
    }

    @Override
    public void declareRoles(String... roleNames)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getVirtualServerName()
    {
        return null;
    }

    @Override
    public int getSessionTimeout()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setSessionTimeout(int sessionTimeout)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getRequestCharacterEncoding()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setRequestCharacterEncoding(String encoding)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getResponseCharacterEncoding()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setResponseCharacterEncoding(String encoding)
    {
        throw new UnsupportedOperationException();
    }

    ContextHandler.ScopedContext getTargetContext()
    {
        return _targetContext;
    }
}
