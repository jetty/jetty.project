//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ExternalFastCGIServerTest
{
    @Test
    @Disabled("Relies on an external server")
    public void testExternalFastCGIServer() throws Exception
    {
        // Assume a FastCGI server is listening on localhost:9000

        HttpClient client = new HttpClient(new HttpClientTransportOverFCGI("/var/www/php-fcgi"));
        client.start();

        ContentResponse response = client.newRequest("localhost", 9000)
            .path("/index.php")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());

        Path responseFile = Paths.get(System.getProperty("java.io.tmpdir"), "fcgi_response.html");
        Files.write(responseFile, response.getContent(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }
}
