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
 * LDAPLeakPreventer
 *
 * If com.sun.jndi.LdapPoolManager class is loaded and the system property
 * com.sun.jndi.ldap.connect.pool.timeout is set to a nonzero value, a daemon
 * thread is started which can pin a webapp classloader if it is the first to
 * load the LdapPoolManager.
 *
 * Inspired by Tomcat JreMemoryLeakPrevention
 */
public class LDAPLeakPreventer extends AbstractLeakPreventer
{

    @Override
    public void prevent(ClassLoader loader)
    {
        try
        {
            Class.forName("com.sun.jndi.LdapPoolManager", true, loader);
        }
        catch (ClassNotFoundException e)
        {
            LOG.trace("IGNORED", e);
        }
    }
}
