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

import java.util.EnumSet;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.eclipse.jetty.ee.servlet.Source;
import org.eclipse.jetty.util.annotation.ManagedObject;

/**
 * Servlet HttpHandler.
 * <p>
 * This handler maps requests to servlets that implement the
 * jakarta.servlet.http.HttpServlet API.
 * <P>
 * This handler does not implement the full J2EE features and is intended to
 * be used directly when a full web application is not required.  If a Web application is required,
 * then this handler should be used as part of a <code>org.eclipse.jetty.webapp.WebAppContext</code>.
 * <p>
 * Unless run as part of a {@link ServletContextHandler} or derivative, the {@link #initialize()}
 * method must be called manually after start().
 */
@ManagedObject("Servlet Handler")
public class ServletHandler extends org.eclipse.jetty.ee.servlet.ServletHandler
{
    public ServletHandler()
    {
        super();
    }

    public FilterHolder addFilterWithMapping(Class<? extends Filter> filter, String pathSpec, EnumSet<DispatcherType> dispatches)
    {
        return (FilterHolder)super.addFilterWithMapping(filter, pathSpec, dispatches);
    }

    @Override
    public ServletHolder newServletHolder(Source source)
    {
        return new ServletHolder(source);
    }
}
