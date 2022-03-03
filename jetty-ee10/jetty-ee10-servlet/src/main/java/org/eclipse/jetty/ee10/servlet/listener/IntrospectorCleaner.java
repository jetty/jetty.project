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

package org.eclipse.jetty.ee10.servlet.listener;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

/**
 * IntrospectorCleaner
 *
 * Cleans a static cache of Methods held by java.beans.Introspector
 * class when a context is undeployed.
 *
 * @see java.beans.Introspector
 */
public class IntrospectorCleaner implements ServletContextListener
{

    @Override
    public void contextInitialized(ServletContextEvent sce)
    {

    }

    @Override
    public void contextDestroyed(ServletContextEvent sce)
    {
        java.beans.Introspector.flushCaches();
    }
}
