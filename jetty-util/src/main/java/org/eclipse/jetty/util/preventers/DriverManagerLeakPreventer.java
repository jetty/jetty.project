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
            LOG.debug("Pinning DriverManager classloader with " + loader);
        DriverManager.getDrivers();
    }
}
