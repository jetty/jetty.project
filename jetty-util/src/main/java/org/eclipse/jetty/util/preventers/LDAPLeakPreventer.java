//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

/**
 * LDAPLeakPreventer
 *
 * If com.sun.jndi.LdapPoolManager class is loaded and the system property
 * com.sun.jndi.ldap.connect.pool.timeout is set to a nonzero value, a daemon
 * thread is started which can pin a webapp classloader if it is the first to
 * load the LdapPoolManager.
 * 
 * Inspired by Tomcat JreMemoryLeakPrevention
 *
 */
public class LDAPLeakPreventer extends AbstractLeakPreventer
{
    /* ------------------------------------------------------------ */
    /** 
     * @see org.eclipse.jetty.util.preventers.AbstractLeakPreventer#prevent(java.lang.ClassLoader)
     */
    @Override
    public void prevent(ClassLoader loader)
    {
        try
        {
            Class.forName("com.sun.jndi.LdapPoolManager", true, loader);
        }
        catch (ClassNotFoundException e)
        {
            LOG.ignore(e);
        }
    }

}
