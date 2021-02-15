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

package org.eclipse.jetty.cdi;

import org.eclipse.jetty.servlet.DecoratingListener;
import org.eclipse.jetty.servlet.ServletContextHandler;

/**
 * A DecoratingListener that listens for "org.eclipse.jetty.cdi.decorator"
 */
public class CdiDecoratingListener extends DecoratingListener
{
    public static final String MODE = "CdiDecoratingListener";
    public static final String ATTRIBUTE = "org.eclipse.jetty.cdi.decorator";

    public CdiDecoratingListener(ServletContextHandler contextHandler)
    {
        super(contextHandler, ATTRIBUTE);
        contextHandler.setAttribute(CdiServletContainerInitializer.CDI_INTEGRATION_ATTRIBUTE, MODE);
    }
}
