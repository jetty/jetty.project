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

package org.eclipse.jetty.server.session;

import org.junit.After;
import org.junit.Test;

/**
 * LocalSessionScavengingTest
 */
public class LocalSessionScavengingTest extends AbstractLocalSessionScavengingTest
{
    public void pause (int scavenge)
    {
        //Wait a little longer for the scavenging to happen with the JDBCSession handling.
        //The scavenging happens at about +10% longer than the scavenge interval, so that
        //not all nodes sync up and start trying to scavenge for the same sessions at the
        //same time.
        //So, we wait 3 times the scavenging interval.
        try
        {
            Thread.sleep(scavenge * 3000);
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
    }

    public AbstractTestServer createServer(int port, int max, int scavenge, int evictionPolicy) throws Exception
    {
        return new JdbcTestServer(port,max,scavenge, evictionPolicy);
    }

    @Test
    public void testNoScavenging() throws Exception
    {
        super.testNoScavenging();
    }
    
    
    @Test
    public void testLocalSessionsScavenging() throws Exception
    {
        super.testLocalSessionsScavenging();
    }

    
    @After
    public void tearDown() throws Exception 
    {
        JdbcTestServer.shutdown(null);
    }
}
