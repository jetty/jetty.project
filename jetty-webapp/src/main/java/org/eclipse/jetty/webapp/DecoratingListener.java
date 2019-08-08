//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.util.Decorator;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * A ServletContextAttributeListener that listens for a specific context
 * attribute (default "org.eclipse.jetty.webapp.decorator") to obtain a
 * decorator instance from the webapp.  The instance is then either coerced
 * to a Decorator or reflected for decorator compatible methods so it can
 * be added to the {@link WebAppContext#getObjectFactory()} as a
 * {@link Decorator}.
 * The context attribute "org.eclipse.jetty.webapp.DecoratingListener" if
 * not set, is set to the name of the attribute this listener listens for.
 */
public class DecoratingListener extends org.eclipse.jetty.servlet.DecoratingListener
{
    public static final String DECORATOR_ATTRIBUTE = "org.eclipse.jetty.webapp.decorator";
    private static final Logger LOG = Log.getLogger(DecoratingListener.class);

    public DecoratingListener()
    {
        this((String)null);
    }

    public DecoratingListener(String attributeName)
    {
        this(WebAppContext.getCurrentWebAppContext(), attributeName);
    }

    public DecoratingListener(ServletContextHandler context)
    {
        this(context, null);
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
