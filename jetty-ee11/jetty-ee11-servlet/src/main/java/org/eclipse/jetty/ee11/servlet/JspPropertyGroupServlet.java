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

import org.eclipse.jetty.server.handler.ContextHandler;

/**
 * Servlet handling JSP Property Group mappings
 * <p>
 * This servlet is mapped to by any URL pattern for a JSP property group.
 * Resources handled by this servlet that are not directories will be passed
 * directly to the JSP servlet.    Resources that are directories will be
 * passed directly to the default servlet.
 */
public class JspPropertyGroupServlet extends org.eclipse.jetty.ee.servlet.JspPropertyGroupServlet
{
    public JspPropertyGroupServlet(ContextHandler context, ServletHandler servletHandler)
    {
        super(context, servletHandler);
    }
}
