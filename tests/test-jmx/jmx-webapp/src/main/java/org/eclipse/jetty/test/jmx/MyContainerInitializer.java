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

package org.eclipse.jetty.test.jmx;

import java.util.Set;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MyContainerInitializer implements ServletContainerInitializer
{
    private static final Logger LOG = LoggerFactory.getLogger(MyContainerInitializer.class);

    @Override
    public void onStartup(Set<Class<?>> c, ServletContext ctx) throws ServletException
    {
        // Directly annotated with @ManagedObject
        CommonComponent common = new CommonComponent();
        LOG.info("Initializing {}", common.getClass().getName());
        ctx.setAttribute("org.eclipse.jetty.test.jmx.common", common);

        // Indirectly managed via a MBean
        ctx.setAttribute("org.eclipse.jetty.test.jmx.ping", new Pinger());
        ctx.setAttribute("org.eclipse.jetty.test.jmx.echo", new Echoer());
    }
}
