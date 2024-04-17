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

package org.eclipse.jetty.ee10.log4j2;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class LogContextListener implements ServletContextListener
{
    private static final Logger LOG = LogManager.getLogger(LogContextListener.class);

    @Override
    public void contextInitialized(ServletContextEvent sce)
    {
        LOG.info("contextInitialized(): {}", sce);
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce)
    {
        LOG.info("contextDestroyed(): {}", sce);
    }
}
