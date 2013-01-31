//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/**
 * IdleTimeoutTest
 *
 * Warning - this is a slow test. Uncomment the ignore to run it.
 *
 */
public class IdleTimeoutTest
{
    public int _repetitions = 30;
    
    @Ignore
    //@Test
    public void testIdleTimeoutOnBlockingConnector() throws Exception
    {
        final HttpClient client = new HttpClient();
        client.setMaxConnectionsPerAddress(4);
        client.setConnectorType(HttpClient.CONNECTOR_SOCKET);
        client.setTimeout(TimeUnit.SECONDS.toMillis(86400)); // very long timeout on data
        client.setIdleTimeout(500); // very short idle timeout
        client.start();

        final CountDownLatch counter = new CountDownLatch(_repetitions);
        
        Thread runner = new Thread()
        {
            public void run()
            {
                try
                {
                    for (int i=0; i<_repetitions; i++) 
                    {
                        ContentExchange exchange = new ContentExchange();
                        exchange.setURL("http://www.google.com/?i="+i);
                        client.send(exchange);
                        exchange.waitForDone();
                        counter.countDown();
                        System.err.println(counter.getCount());
                        Thread.sleep(1000); //wait long enough for idle timeout to expire   
                    }
                }
                catch (Exception e)
                {
                    Assert.fail(e.getMessage());
                }
            }
        };
       
        runner.start();
        if (!counter.await(80, TimeUnit.SECONDS))
            Assert.fail("Test did not complete in time");
        
    }
}
