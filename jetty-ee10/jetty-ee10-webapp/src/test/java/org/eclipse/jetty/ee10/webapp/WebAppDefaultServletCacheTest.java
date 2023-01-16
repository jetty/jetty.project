//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.webapp;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class WebAppDefaultServletCacheTest
{
    private LocalConnector connector;
    private Server server;
    private Path resourcePath;

    @BeforeEach
    protected void setUp() throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);

        URI uri = getClass().getResource("/org/acme").toURI();
        resourcePath = Paths.get(uri);
        server.addHandler(new WebAppContext(uri.toString(), "/"));

        server.start();
    }

    @AfterEach
    protected void tearDown() throws Exception
    {
        server.stop();
    }

    @Test
    public void testCacheRefreshing() throws Exception
    {
        String fileName = "jetty-pic.png";
        long fileSize = Files.size(resourcePath.resolve(fileName));

        HttpTester.Response response1 = sendRequest(fileName);
        assertEquals(response1.getLongField("Content-Length"), fileSize);
        assertEquals(200, response1.getStatus());

        HttpTester.Response response2 = sendRequest(fileName, "If-Modified-Since: " + response1.get("Last-Modified"));
        assertEquals(response2.getLongField("Content-Length"), 0L);
        assertEquals(304, response2.getStatus());

        HttpTester.Response response3 = sendRequest(fileName, "Cache-Control: no-cache", "Pragma: no-cache");
        assertEquals(response3.getLongField("Content-Length"), fileSize);
        assertEquals(200, response3.getStatus());
    }

    private HttpTester.Response sendRequest(String file, String... extraHeaders) throws Exception
    {
        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /").append(file).append(" HTTP/1.1\r\n");
        rawRequest.append("Host: local\r\n");
        for (String extraHeader : extraHeaders)
        {
            rawRequest.append(extraHeader).append("\r\n");
        }
        rawRequest.append("\r\n");
        String rawResponse = connector.getResponse(rawRequest.toString());
        return HttpTester.parseResponse(rawResponse);
    }
}
