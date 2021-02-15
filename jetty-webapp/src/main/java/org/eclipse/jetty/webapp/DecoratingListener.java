//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.webapp;

import org.eclipse.jetty.servlet.ServletContextHandler;

/**
 * An extended org.eclipse.jetty.servlet.DecoratingListener.
 * The context attribute "org.eclipse.jetty.webapp.DecoratingListener" if
 * not set, is set to the name of the attribute this listener listens for.
 */
public class DecoratingListener extends org.eclipse.jetty.servlet.DecoratingListener
{
    public static final String DECORATOR_ATTRIBUTE = "org.eclipse.jetty.webapp.decorator";

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
