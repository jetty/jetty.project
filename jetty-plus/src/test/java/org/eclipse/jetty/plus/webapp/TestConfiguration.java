// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.plus.webapp;

import javax.naming.Context;
import javax.naming.InitialContext;

import junit.framework.TestCase;

import org.eclipse.jetty.plus.jndi.EnvEntry;
import org.eclipse.jetty.plus.jndi.NamingEntry;
import org.eclipse.jetty.plus.jndi.NamingEntryUtil;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.webapp.WebAppContext;

public class TestConfiguration extends TestCase
{
    
    public class MyWebAppContext extends WebAppContext
    {
        public String toString()
        {
            return this.getClass().getName()+"@"+super.hashCode();
        }
    }
    
    public void testIt ()
    throws Exception
    {
        ClassLoader old_loader = Thread.currentThread().getContextClassLoader();

        try
        {
            InitialContext ic = new InitialContext();

            Server server = new Server();

            WebAppContext wac = new MyWebAppContext();
            wac.setServer(server);
            wac.setClassLoader(new WebAppClassLoader(Thread.currentThread().getContextClassLoader(), wac));

            //bind some EnvEntrys at the server level 
            EnvEntry ee1 = new EnvEntry(server, "xxx/a", "100", true);
            EnvEntry ee2 = new EnvEntry(server, "yyy/b", "200", false);
            EnvEntry ee3 = new EnvEntry(server, "zzz/c", "300", false);
            EnvEntry ee4 = new EnvEntry(server, "zzz/d", "400", false);
            EnvEntry ee5 = new EnvEntry(server, "zzz/f", "500", true);

            //bind some EnvEntrys at the webapp level 
            EnvEntry ee6 = new EnvEntry(wac, "xxx/a", "900", true);
            EnvEntry ee7 = new EnvEntry(wac, "yyy/b", "910", true);
            EnvEntry ee8 = new EnvEntry(wac, "zzz/c", "920", false);
            EnvEntry ee9 = new EnvEntry(wac, "zzz/e", "930", false);

            assertNotNull(NamingEntryUtil.lookupNamingEntry(server, "xxx/a"));
            assertNotNull(NamingEntryUtil.lookupNamingEntry(server, "yyy/b"));
            assertNotNull(NamingEntryUtil.lookupNamingEntry(server, "zzz/c"));
            assertNotNull(NamingEntryUtil.lookupNamingEntry(server, "zzz/d"));
            assertNotNull(NamingEntryUtil.lookupNamingEntry(wac, "xxx/a"));
            assertNotNull(NamingEntryUtil.lookupNamingEntry(wac, "yyy/b"));
            assertNotNull(NamingEntryUtil.lookupNamingEntry(wac, "zzz/c")); 
            assertNotNull(NamingEntryUtil.lookupNamingEntry(wac, "zzz/e"));

            Configuration config = new Configuration();

            EnvConfiguration envConfig = new EnvConfiguration();


            Thread.currentThread().setContextClassLoader(wac.getClassLoader());
            envConfig.preConfigure(wac);
            envConfig.configure(wac);
            envConfig.bindEnvEntries(wac);

            String val = (String)ic.lookup("java:comp/env/xxx/a");
            assertEquals("900", val); //webapp naming overrides server
            val = (String)ic.lookup("java:comp/env/yyy/b");
            assertEquals("910", val);//webapp overrides server
            val = (String)ic.lookup("java:comp/env/zzz/c");
            assertEquals("920",val);//webapp overrides server
            val = (String)ic.lookup("java:comp/env/zzz/d");
            assertEquals("400", val);//from server naming
            val = (String)ic.lookup("java:comp/env/zzz/e");
            assertEquals("930", val);//from webapp naming

            NamingEntry ne = (NamingEntry)ic.lookup("java:comp/env/"+NamingEntry.__contextName+"/xxx/a");
            assertNotNull(ne);
            ne = (NamingEntry)ic.lookup("java:comp/env/"+NamingEntry.__contextName+"/yyy/b");
            assertNotNull(ne);
            ne = (NamingEntry)ic.lookup("java:comp/env/"+NamingEntry.__contextName+"/zzz/c");
            assertNotNull(ne);
            ne = (NamingEntry)ic.lookup("java:comp/env/"+NamingEntry.__contextName+"/zzz/d");
            assertNotNull(ne);
            ne = (NamingEntry)ic.lookup("java:comp/env/"+NamingEntry.__contextName+"/zzz/e");
            assertNotNull(ne);

            config.bindEnvEntry(wac, "foo", "99");
            assertEquals("99",ic.lookup( "java:comp/env/foo"));

            config.bindEnvEntry(wac, "xxx/a", "7");
            assertEquals("900", ic.lookup("java:comp/env/xxx/a")); //webapp overrides web.xml
            config.bindEnvEntry(wac, "yyy/b", "7");
            assertEquals("910", ic.lookup("java:comp/env/yyy/b"));//webapp overrides web.xml
            config.bindEnvEntry(wac,"zzz/c", "7");
            assertEquals("7", ic.lookup("java:comp/env/zzz/c"));//webapp does NOT override web.xml
            config.bindEnvEntry(wac,"zzz/d", "7");
            assertEquals("7", ic.lookup("java:comp/env/zzz/d"));//server does NOT override web.xml
            config.bindEnvEntry(wac,"zzz/e", "7");
            assertEquals("7", ic.lookup("java:comp/env/zzz/e"));//webapp does NOT override web.xml
            config.bindEnvEntry(wac,"zzz/f", "7");
            assertEquals("500", ic.lookup("java:comp/env/zzz/f"));//server overrides web.xml

            ((Context)ic.lookup("java:comp")).destroySubcontext("env");
            ic.destroySubcontext("xxx");
            ic.destroySubcontext("yyy");
            ic.destroySubcontext("zzz");
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old_loader);
        }
    }
}
