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

package org.eclipse.jetty.maven.plugin.it;

import java.io.LineNumberReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IntegrationTestGetContent
{
    @Test
    public void getContentResponse()
        throws Exception
    {
        int port = getPort();
        assertTrue(port > 0);
        String contextPath = getContextPath();
        if (contextPath.endsWith("/"))
            contextPath = contextPath.substring(0, contextPath.lastIndexOf('/'));

        HttpClient httpClient = new HttpClient();
        try
        {
            httpClient.start();

            if (Boolean.getBoolean("helloServlet"))
            {
                String response = httpClient.GET("http://localhost:" + port + contextPath + "/hello?name=beer").getContentAsString();
                assertEquals("Hello beer", response.trim(), "it test " + System.getProperty("maven.it.name"));
                response = httpClient.GET("http://localhost:" + port + contextPath + "/hello?name=foo").getContentAsString();
                assertEquals("Hello foo", response.trim(), "it test " + System.getProperty("maven.it.name"));
                System.out.println("helloServlet");
            }
            if (Boolean.getBoolean("pingServlet"))
            {
                System.out.println("pingServlet");
                String response = httpClient.GET("http://localhost:" + port + contextPath + "/ping?name=beer").getContentAsString();
                assertEquals("pong beer", response.trim(), "it test " + System.getProperty("maven.it.name"));
                System.out.println("pingServlet ok");
            }
            String contentCheck = System.getProperty("contentCheck");
            String pathToCheck = System.getProperty("pathToCheck");
            System.out.println("contentCheck: " + contentCheck);
            if (StringUtils.isNotBlank(contentCheck))
            {
                String url = "http://localhost:" + port + contextPath;
                if (pathToCheck != null)
                {
                    url += pathToCheck;
                }
                String response = httpClient.GET(url).getContentAsString();
                assertTrue(response.contains(contentCheck), "it test " + System.getProperty("maven.it.name") +
                    ", response not contentCheck: " + contentCheck + ", response:" + response);
                System.out.println("contentCheck");
            }
            if (Boolean.getBoolean("helloTestServlet"))
            {
                String response = httpClient.GET("http://localhost:" + port + contextPath + "/testhello?name=beer").getContentAsString();
                assertEquals("Hello from test beer", response.trim(), "it test " + System.getProperty("maven.it.name"));
                response = httpClient.GET("http://localhost:" + port + contextPath + "/testhello?name=foo").getContentAsString();
                assertEquals("Hello from test foo", response.trim(), "it test " + System.getProperty("maven.it.name"));
                System.out.println("helloServlet");
            }
        }
        finally
        {
            httpClient.stop();
        }
    }

    public static String getContextPath()
    {
        return System.getProperty("context.path", "/");
    }

    public static int getPort()
        throws Exception
    {
        int attempts = 70;
        int port = -1;
        String s = System.getProperty("jetty.port.file");
        assertNotNull(s, "jetty.port.file System property");
        Path p = Paths.get(s);
        System.err.println("Looking for port file: " + p);
        while (true)
        {
            if (Files.exists(p))
            {
                try (Reader r = Files.newBufferedReader(p);
                     LineNumberReader lnr = new LineNumberReader(r);)
                {
                    s = lnr.readLine();
                    assertNotNull(s);
                    port = Integer.parseInt(s.trim());
                }
                break;
            }
            else
            {
                if (--attempts < 0)
                {
                    break;
                }
                else
                {
                    Thread.sleep(1000);
                }
                System.err.printf("  attempts left: #%d%n", attempts);
            }
        }
        return port;
    }
}
