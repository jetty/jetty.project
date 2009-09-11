/*
 * Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package org.eclipse.jetty.client;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

/**
 * @version $Revision$ $Date$
 */
public class ConnectionFailedTest extends TestCase
{
    public void testConnectionFailed() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        CountDownLatch latch = new CountDownLatch(1);
        HttpExchange exchange = new ConnectionFailedExchange(latch);
        exchange.setAddress(new Address("localhost", 8080));
        exchange.setURI("/");

        httpClient.send(exchange);

        boolean passed = latch.await(1000, TimeUnit.MILLISECONDS);
        assertTrue(passed);

        long wait = 100;
        long maxWait = 10 * wait;
        long curWait = wait;
        while (curWait < maxWait && !exchange.isDone(exchange.getStatus()))
        {
            Thread.sleep(wait);
            curWait += wait;
        }

        assertEquals(HttpExchange.STATUS_EXCEPTED, exchange.getStatus());
    }

    private class ConnectionFailedExchange extends HttpExchange
    {
        private final CountDownLatch latch;

        private ConnectionFailedExchange(CountDownLatch latch)
        {
            this.latch = latch;
        }

        @Override
        protected void onConnectionFailed(Throwable ex)
        {
            latch.countDown();
        }
    }
}
