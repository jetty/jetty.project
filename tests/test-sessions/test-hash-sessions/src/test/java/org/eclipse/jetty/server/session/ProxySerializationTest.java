//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.server.session;

import java.io.File;

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.Test;

/**
 * ProxySerializationTest
 *
 *
 */
public class ProxySerializationTest extends AbstractProxySerializationTest
{   
    /** 
     * @see org.eclipse.jetty.server.session.AbstractProxySerializationTest#createServer(int, int, int)
     */
    @Override
    public AbstractTestServer createServer(int port, int max, int scavenge)
    {
        return new HashTestServer(port,max,scavenge);
    }
    
    
    
    
    @Override
    public void customizeContext(ServletContextHandler c)
    {
        if (c == null)
            return;
        
        //Ensure that the HashSessionManager will persist sessions on passivation
        HashSessionManager manager = (HashSessionManager)c.getSessionHandler().getSessionManager();
        manager.setLazyLoad(false);
        manager.setIdleSavePeriod(1);
        try
        {
            File testDir = MavenTestingUtils.getTargetTestingDir("foo");
            testDir.mkdirs();
            manager.setStoreDirectory(testDir);
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }       
    }




    @Test
    public void testProxySerialization() throws Exception
    {
        super.testProxySerialization();
    }

}
