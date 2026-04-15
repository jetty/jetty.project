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

package org.eclipse.jetty.ee11.webapp;

import org.eclipse.jetty.ee.servlet.ErrorHandler;
import org.eclipse.jetty.ee.servlet.ServletHandler;
import org.eclipse.jetty.ee.servlet.SessionHandler;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.util.resource.Resource;

public class WebAppContext
    extends org.eclipse.jetty.ee.webapp.WebAppContext
{
    public WebAppContext()
    {
    }

    public WebAppContext(String webApp, String contextPath)
    {
        super(webApp, contextPath);
    }

    public WebAppContext(Resource webApp, String contextPath)
    {
        super(webApp, contextPath);
    }

    public WebAppContext(SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler)
    {
        super(sessionHandler, securityHandler, servletHandler, errorHandler);
    }

    public WebAppContext(String contextPath, SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler, int options)
    {
        super(contextPath, sessionHandler, securityHandler, servletHandler, errorHandler, options);
    }
}
