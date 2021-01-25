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

package org.eclipse.jetty.test.jmx;

import java.util.Set;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class MyContainerInitializer implements ServletContainerInitializer
{
    private static final Logger LOG = Log.getLogger(MyContainerInitializer.class);

    @Override
    public void onStartup(Set<Class<?>> c, ServletContext ctx) throws ServletException
    {
        // Directly annotated with @ManagedObject
        CommonComponent common = new CommonComponent();
        LOG.info("Initializing " + common.getClass().getName());
        ctx.setAttribute("org.eclipse.jetty.test.jmx.common", common);

        // Indirectly managed via a MBean
        ctx.setAttribute("org.eclipse.jetty.test.jmx.ping", new Pinger());
        ctx.setAttribute("org.eclipse.jetty.test.jmx.echo", new Echoer());
    }
}
