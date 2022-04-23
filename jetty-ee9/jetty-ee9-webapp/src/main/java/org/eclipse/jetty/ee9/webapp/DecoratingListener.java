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

package org.eclipse.jetty.ee9.webapp;

import org.eclipse.jetty.ee9.servlet.ServletContextHandler;

/**
 * An extended org.eclipse.jetty.ee9.servlet.DecoratingListener.
 * The context attribute "org.eclipse.jetty.ee9.webapp.DecoratingListener" if
 * not set, is set to the name of the attribute this listener listens for.
 */
public class DecoratingListener extends org.eclipse.jetty.ee9.servlet.DecoratingListener
{
    public static final String DECORATOR_ATTRIBUTE = "org.eclipse.jetty.ee9.webapp.decorator";

    public DecoratingListener()
    {
        this(DECORATOR_ATTRIBUTE);
    }

    public DecoratingListener(String attributeName)
    {
        this(WebAppContext.getCurrentWebAppContext(), attributeName);
    }

    public DecoratingListener(ServletContextHandler context)
    {
        this(context, DECORATOR_ATTRIBUTE);
    }

    public DecoratingListener(ServletContextHandler context, String attributeName)
    {
        super(context, attributeName);
        checkAndSetAttributeName();
    }

    protected void checkAndSetAttributeName()
    {
        // If not set (by another DecoratingListener), flag the attribute that are
        // listening for.  If more than one DecoratingListener is used then this
        // attribute reflects only the first.
        if (getServletContext().getAttribute(getClass().getName()) != null)
            throw new IllegalStateException("Multiple DecoratingListeners detected");
        getServletContext().setAttribute(getClass().getName(), getAttributeName());
    }
}
