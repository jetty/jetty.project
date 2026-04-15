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

package org.eclipse.jetty.ee10.webapp;

import org.eclipse.jetty.ee.common.WebAppClassLoader;
import org.eclipse.jetty.ee.servlet.ErrorHandler;
import org.eclipse.jetty.ee.servlet.SessionHandler;
import org.eclipse.jetty.ee.webapp.JettyWebXmlConfiguration;
import org.eclipse.jetty.ee.webapp.WebXmlConfiguration;
import org.eclipse.jetty.ee10.servlet.ServletHandler;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.resource.Resource;

/**
 * Web Application Context Handler.
 * <p>
 * The WebAppContext handler is an extension of ContextHandler that
 * coordinates the construction and configuration of nested handlers:
 * {@link org.eclipse.jetty.ee.servlet.security.ConstraintSecurityHandler}, {@link org.eclipse.jetty.ee10.servlet.SessionHandler}
 * and {@link ServletHandler}.
 * The handlers are configured by pluggable configuration classes, with
 * the default being  {@link WebXmlConfiguration} and
 * {@link JettyWebXmlConfiguration}.
 * </p>
 * <p>The class implements {@link WebAppClassLoader.Context} and thus the {@link org.eclipse.jetty.util.ClassVisibilityChecker}
 * API, which is used by any {@link WebAppClassLoader} to control visibility of classes to the context.</p>
 */
@ManagedObject("Web Application ContextHandler")
public class WebAppContext extends org.eclipse.jetty.ee.webapp.WebAppContext
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

    public WebAppContext(SessionHandler sessionHandler, SecurityHandler securityHandler, org.eclipse.jetty.ee.servlet.ServletHandler servletHandler, ErrorHandler errorHandler)
    {
        super(sessionHandler, securityHandler, servletHandler, errorHandler);
    }

    public WebAppContext(String contextPath, SessionHandler sessionHandler, SecurityHandler securityHandler, org.eclipse.jetty.ee.servlet.ServletHandler servletHandler, ErrorHandler errorHandler, int options)
    {
        super(contextPath, sessionHandler, securityHandler, servletHandler, errorHandler, options);
    }
}
