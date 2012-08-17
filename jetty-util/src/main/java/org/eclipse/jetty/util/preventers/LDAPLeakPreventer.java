//========================================================================
//Copyright 2012 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at 
//http://www.apache.org/licenses/LICENSE-2.0
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.
//========================================================================

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
