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

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import junit.framework.TestCase;

/**
 * @version $Revision$ $Date$
 */
public class AsyncCallbackHttpExchangeTest extends TestCase
{
    /**
     * If the HttpExchange callbacks are called holding the lock on HttpExchange,
     * it will be impossible for the callback to perform some work asynchronously
     * and contemporarly accessing the HttpExchange instance synchronized state.
     * This test verifies that this situation does not happen.
     *
     * @throws Exception if the test fails
     */
    public void testAsyncCallback() throws Exception
    {
        ExecutorService executor = Executors.newCachedThreadPool();
        try
        {
            AtomicReference<Exception> failure = new AtomicReference<Exception>();
            TestHttpExchange exchange = new TestHttpExchange(executor, failure);
            exchange.setStatus(HttpExchange.STATUS_WAITING_FOR_COMMIT);
            exchange.setStatus(HttpExchange.STATUS_SENDING_REQUEST);
            // This status change triggers onRequestCommitted()
            exchange.setStatus(HttpExchange.STATUS_WAITING_FOR_RESPONSE);
            assertNull(failure.get());
        }
        finally
        {
            executor.shutdown();
        }
    }

    private class TestHttpExchange extends HttpExchange
    {
        private final ExecutorService executor;
        private final AtomicReference<Exception> failure;

        private TestHttpExchange(ExecutorService executor, AtomicReference<Exception> failure)
        {
            this.executor = executor;
            this.failure = failure;
        }

        @Override
        protected void onRequestCommitted() throws IOException
        {
            Future<Integer> future = executor.submit(new Callable<Integer>()
            {
                public Integer call() throws Exception
                {
                    // Method getStatus() reads synchronized state
                    return TestHttpExchange.this.getStatus();
                }
            });

            // We're waiting for the future to complete, thus never exiting
            // this method; if this method is called with the lock held,
            // this method never completes
            try
            {
                future.get(1000, TimeUnit.MILLISECONDS);
                // Test green here
            }
            catch (Exception x)
            {
                // Timed out, the test did not pass
                failure.set(x);
            }
        }
    }
}
