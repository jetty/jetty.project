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

import java.io.IOException;
import java.net.Socket;

import org.eclipse.jetty.http.HttpSchemes;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

public class ExternalSiteTest
{
    @Test
    public void testExternalSSLSite() throws Exception
    {
        HttpClient client = new HttpClient(new SslContextFactory());
        client.start();

        String host = "api-3t.paypal.com";
        int port = 443;

        // Verify that we have connectivity
        try
        {
            new Socket(host, port).close();
        }
        catch (IOException x)
        {
            Assume.assumeNoException(x);
        }

        ContentExchange exchange = new ContentExchange(true);
        exchange.setScheme(HttpSchemes.HTTPS_BUFFER);
        exchange.setAddress(new Address(host, port));
        exchange.setRequestURI("/nvp");
        client.send(exchange);
        int done = exchange.waitForDone();
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, done);
        Assert.assertEquals(HttpStatus.OK_200, exchange.getResponseStatus());
        Assert.assertNotNull(exchange.getResponseContentBytes());

        client.stop();
    }
}
