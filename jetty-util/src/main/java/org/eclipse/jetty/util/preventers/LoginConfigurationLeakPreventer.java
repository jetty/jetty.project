//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.preventers;

/**
 * LoginConfigurationLeakPreventer
 *
 * The javax.security.auth.login.Configuration class keeps a static reference to the
 * thread context classloader. We prevent a webapp context classloader being used for
 * that by invoking the classloading here.
 *
 * Inspired by Tomcat JreMemoryLeakPrevention
 */
public class LoginConfigurationLeakPreventer extends AbstractLeakPreventer
{

    @Override
    public void prevent(ClassLoader loader)
    {
        try
        {
            Class.forName("javax.security.auth.login.Configuration", true, loader);
        }
        catch (ClassNotFoundException e)
        {
            LOG.warn("Unable to load login config", e);
        }
    }
}
