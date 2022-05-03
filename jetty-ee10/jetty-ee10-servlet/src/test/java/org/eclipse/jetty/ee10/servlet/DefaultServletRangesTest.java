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

package org.eclipse.jetty.ee10.servlet;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Disabled
@ExtendWith(WorkDirExtension.class)
public class DefaultServletRangesTest
{
    public static final String DATA = "01234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWZYZ!@#$%^&*()_+/.,[]";
    public WorkDir testdir;

    private Server server;
    private LocalConnector connector;
    private ServletContextHandler context;

    @BeforeEach
    public void init() throws Exception
    {
        server = new Server();

        connector = new LocalConnector(server);
        connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendServerVersion(false);

        context = new ServletContextHandler();
        context.setContextPath("/context");
        context.setWelcomeFiles(new String[]{"index.html", "index.jsp", "index.htm"});

        server.setHandler(context);
        server.addConnector(connector);

        testdir.ensureEmpty();
        File resBase = testdir.getPathFile("docroot").toFile();
        FS.ensureDirExists(resBase);
        File data = new File(resBase, "data.txt");
        createFile(data, DATA);
        String resBasePath = resBase.getAbsolutePath();

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("acceptRanges", "true");
        defholder.setInitParameter("resourceBase", resBasePath);

        server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        server.stop();
        server.join();
    }

    @Test
    public void testNoRangeRequests() throws Exception
    {
        String response;

        response = connector.getResponse(
            "GET /context/data.txt HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n");
        assertResponseContains("200 OK", response);
        assertResponseContains("Accept-Ranges: bytes", response);
        assertResponseContains(DATA, response);
    }

    @Test
    public void testPrefixRangeRequests() throws Exception
    {
        String response;

        response = connector.getResponse(
            "GET /context/data.txt HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "Range: bytes=0-9\r\n" +
                "\r\n");
        assertResponseContains("206 Partial", response);
        assertResponseContains("Content-Type: text/plain", response);
        assertResponseContains("Content-Range: bytes 0-9/80", response);
        assertResponseContains(DATA.substring(0, 10), response);
    }

    @Test
    public void testSingleRangeRequests() throws Exception
    {
        String response;

        response = connector.getResponse(
            "GET /context/data.txt HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "Range: bytes=3-9\r\n" +
                "\r\n");
        assertResponseContains("206 Partial", response);
        assertResponseContains("Content-Type: text/plain", response);
        assertResponseContains("Content-Range: bytes 3-9/80", response);
        assertResponseContains(DATA.substring(3, 10), response);
    }

    @Test
    public void testMultipleRangeRequests() throws Exception
    {
        String response;
        response = connector.getResponse(
            "GET /context/data.txt HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "Range: bytes=0-9,20-29,40-49\r\n" +
                "\r\n");
        int start = response.indexOf("--jetty");
        String body = response.substring(start);
        String boundary = body.substring(0, body.indexOf("\r\n"));
        assertResponseContains("206 Partial", response);
        assertResponseContains("Content-Type: multipart/byteranges; boundary=", response);

        String section1 = boundary + "\r\n" +
            "Content-Type: text/plain\r\n" +
            "Content-Range: bytes 0-9/80\r\n" +
            "\r\n" +
            DATA.substring(0, 10) + "\r\n";
        assertResponseContains(section1, response);

        String section2 = boundary + "\r\n" +
            "Content-Type: text/plain\r\n" +
            "Content-Range: bytes 20-29/80\r\n" +
            "\r\n" +
            DATA.substring(20, 30) + "\r\n";
        assertResponseContains(section2, response);

        String section3 = boundary + "\r\n" +
            "Content-Type: text/plain\r\n" +
            "Content-Range: bytes 40-49/80\r\n" +
            "\r\n" +
            DATA.substring(40, 50) + "\r\n";
        assertResponseContains(section3, response);

        assertTrue(body.endsWith(boundary + "--\r\n"));
    }

    @Test
    public void testMultipleSameRangeRequests() throws Exception
    {
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < 1000; i++)
        {
            stringBuilder.append("10-60,");
        }

        String response;
        response = connector.getResponse(
            "GET /context/data.txt HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "Range: bytes=" + stringBuilder.toString() + "0-2\r\n" +
                "\r\n");
        int start = response.indexOf("--jetty");
        String body = response.substring(start);
        String boundary = body.substring(0, body.indexOf("\r\n"));
        assertResponseContains("206 Partial", response);
        assertResponseContains("Content-Type: multipart/byteranges; boundary=", response);

        assertResponseContains("Content-Range: bytes 10-60/80", response);
        assertResponseContains("Content-Range: bytes 0-2/80", response);
        assertEquals(2, response.split("Content-Range: bytes 10-60/80").length, //
            "Content range 0-60/80 in response not only 1:" + response);
        assertTrue(body.endsWith(boundary + "--\r\n"));
    }

    @Test
    public void testMultipleSameRangeRequestsTooLargeHeader() throws Exception
    {
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < 2000; i++)
        {
            stringBuilder.append("10-60,");
        }

        String response;
        response = connector.getResponse(
            "GET /context/data.txt HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "Range: bytes=" + stringBuilder.toString() + "0-2\r\n" +
                "\r\n");
        int start = response.indexOf("--jetty");
        assertEquals(-1, start);
        assertResponseContains("HTTP/1.1 431 Request Header Fields Too Large", response);
    }

    @Test
    public void testOpenEndRange() throws Exception
    {
        String response;
        response = connector.getResponse(
            "GET /context/data.txt HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "Range: bytes=20-\r\n" +
                "\r\n");
        assertResponseContains("206 Partial", response);
        assertResponseNotContains("Content-Type: multipart/byteranges; boundary=", response);
        assertResponseContains("Content-Range: bytes 20-79/80", response);
        assertResponseContains(DATA.substring(60), response);
    }

    @Test
    public void testOpenStartRange() throws Exception
    {
        String response;
        response = connector.getResponse(
            "GET /context/data.txt HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "Range: bytes=-20\r\n" +
                "\r\n");
        assertResponseContains("206 Partial", response);
        assertResponseNotContains("Content-Type: multipart/byteranges; boundary=", response);
        assertResponseContains("Content-Range: bytes 60-79/80", response); // yes the spec says it is these bytes
        assertResponseContains(DATA.substring(60), response);
    }

    @Test
    public void testUnsatisfiableRanges() throws Exception
    {
        String response;
        response = connector.getResponse(
            "GET /context/data.txt HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "Range: bytes=100-110\r\n" +
                "\r\n");
        assertResponseContains("416 Range Not Satisfiable", response);
    }

    private void createFile(File file, String str) throws IOException
    {
        try (OutputStream out = Files.newOutputStream(file.toPath()))
        {
            out.write(str.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private void assertResponseNotContains(String forbidden, String response)
    {
        assertThat(response, Matchers.not(Matchers.containsString(forbidden)));
    }

    private int assertResponseContains(String expected, String response)
    {
        assertThat(response, Matchers.containsString(expected));
        return response.indexOf(expected);
    }
}
