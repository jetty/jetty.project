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

package org.eclipse.jetty.ee11.servlet;

import jakarta.servlet.ServletContext;
import jakarta.servlet.UnavailableException;
import org.eclipse.jetty.ee.BaseHolder;
import org.eclipse.jetty.ee.Source;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

/**
 * Specialization of BaseHolder for servlet-related classes
 *
 * @param <T> the type of holder
 */
@ManagedObject("Holder - a container for servlets and the like")
public abstract class ServletContextHolder<T> extends BaseHolder<T>
{
    private ServletHandler _servletHandler;
    private String _displayName;
    private String _name;

    protected ServletContextHolder(Source source)
    {
        super(source);
    }

    @Override
    public ContextHandler getContextHandler()
    {
        ContextHandler contextHandler = super.getContextHandler();
        if (contextHandler == null && _servletHandler != null && _servletHandler.getServletContextHandler() != null)
        {
            contextHandler = _servletHandler.getServletContextHandler();
            super.setContextHandler(contextHandler);
        }
        return contextHandler;
    }

    public ServletContext getServletContext()
    {
        if (_servletHandler != null)
            return _servletHandler.getServletContext();
        if (getContextHandler() != null && getContextHandler().getContext() instanceof ServletContextHandler.ServletScopedContext servletScopedContext)
            return servletScopedContext.getServletContext();
        return null;
    }

    public ServletContextHandler getServletContextHandler()
    {
        if (_servletHandler != null && _servletHandler.getServletContextHandler() != null)
            return _servletHandler.getServletContextHandler();
        if (getContextHandler() != null && getContextHandler().getContext() instanceof ServletContextHandler.ServletScopedContext servletScopedContext)
            return servletScopedContext.getServletContextHandler();
        return null;
    }

    public ServletHandler getServletHandler()
    {
        if (_servletHandler != null)
            return _servletHandler;
        if (getContextHandler() != null && getContextHandler().getContext() instanceof ServletContextHandler.ServletScopedContext servletScopedContext)
            return servletScopedContext.getServletContextHandler().getServletHandler();
        return null;
    }

    public void setServletHandler(ServletHandler handler)
    {
        _servletHandler = handler;
        if (_servletHandler.getServletContextHandler() != null)
            setContextHandler(_servletHandler.getServletContextHandler());
    }

    @Override
    protected Exception newUnavailableException(String message)
    {
        return new UnavailableException(message);
    }

    @ManagedAttribute(value = "Display Name", readonly = true)
    public String getDisplayName()
    {
        return _displayName;
    }

    @ManagedAttribute(value = "Name", readonly = true)
    public String getName()
    {
        return _name;
    }

    /**
     * @param className The className to set.
     */
    @Override
    public void setClassName(String className)
    {
        super.setClassName(className);
        if (_name == null)
            _name = className + "-" + Integer.toHexString(this.hashCode());
    }

    /**
     * @param held The class to hold
     */
    @Override
    public void setHeldClass(Class<? extends T> held)
    {
        super.setHeldClass(held);
        if (held != null)
        {
            if (_name == null)
                _name = held.getName() + "-" + Integer.toHexString(this.hashCode());
        }
    }

    public void setDisplayName(String name)
    {
        _displayName = name;
    }

    /**
     * The name is a primary key for the held object.
     * Ensure that the name is set BEFORE adding a Holder
     * (eg ServletHolder or FilterHolder) to a ServletHandler.
     *
     * @param name The name to set.
     */
    public void setName(String name)
    {
        _name = name;
    }

    @Override
    public String dump()
    {
        return super.dump();
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x==%s", _name, hashCode(), getClassName());
    }
}





