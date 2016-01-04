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

package org.eclipse.jetty.fcgi.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.fcgi.client.http.HttpClientTransportOverFCGI;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class ExternalFastCGIServerTest
{
    @Test
    @Ignore("Relies on an external server")
    public void testExternalFastCGIServer() throws Exception
    {
        // Assume a FastCGI server is listening on localhost:9000

        HttpClient client = new HttpClient(new HttpClientTransportOverFCGI("/var/www/php-fcgi"), null);
        client.start();

        ContentResponse response = client.newRequest("localhost", 9000)
                .path("/index.php")
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());

        Path responseFile = Paths.get(System.getProperty("java.io.tmpdir"), "fcgi_response.html");
        Files.write(responseFile, response.getContent(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }
}
