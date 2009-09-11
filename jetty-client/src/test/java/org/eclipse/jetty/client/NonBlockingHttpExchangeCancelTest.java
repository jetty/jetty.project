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

/**
 * @version $Revision$ $Date$
 */
public class NonBlockingHttpExchangeCancelTest extends AbstractHttpExchangeCancelTest
{
    private HttpClient httpClient;

    @Override
    protected void setUp() throws Exception
    {
        super.setUp();
        httpClient = new HttpClient();
        httpClient.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        httpClient.start();
    }

    @Override
    protected void tearDown() throws Exception
    {
        httpClient.stop();
        super.tearDown();
    }

    protected HttpClient getHttpClient()
    {
        return httpClient;
    }

    public void testHttpExchangeOnExpire() throws Exception
    {
        HttpClient httpClient = getHttpClient();
        httpClient.stop();
        httpClient.setTimeout(2000);
        httpClient.start();

        TestHttpExchange exchange = new TestHttpExchange();
        exchange.setAddress(newAddress());
        exchange.setURI("/?action=wait5000");

        httpClient.send(exchange);

        int status = exchange.waitForDone();
        assertEquals(HttpExchange.STATUS_EXPIRED, status);
        assertFalse(exchange.isResponseCompleted());
        assertFalse(exchange.isFailed());
        assertTrue(exchange.isExpired());
        assertFalse(exchange.isAssociated());
    }
}
