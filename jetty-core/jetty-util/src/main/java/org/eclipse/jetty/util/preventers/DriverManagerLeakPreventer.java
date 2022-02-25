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

package org.eclipse.jetty.util.preventers;

import java.sql.DriverManager;

/**
 * DriverManagerLeakPreventer
 *
 * Cause DriverManager.getCallerClassLoader() to be called, which will pin the classloader.
 *
 * Inspired by Tomcat JreMemoryLeakPrevention.
 */
public class DriverManagerLeakPreventer extends AbstractLeakPreventer
{

    @Override
    public void prevent(ClassLoader loader)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Pinning DriverManager classloader with {}", loader);
        DriverManager.getDrivers();
    }
}
