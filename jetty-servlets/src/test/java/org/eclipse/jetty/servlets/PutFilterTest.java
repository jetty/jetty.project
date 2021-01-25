//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.servlets;

import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import javax.servlet.DispatcherType;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletTester;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.PathResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class PutFilterTest
{
    public WorkDir workDir;
    private Path root;
    private ServletTester tester;

    @BeforeEach
    public void setUp() throws Exception
    {
        root = workDir.getEmptyPathDir();

        tester = new ServletTester("/context");
        tester.setBaseResource(new PathResource(root));
        tester.addServlet(org.eclipse.jetty.servlet.DefaultServlet.class, "/");
        FilterHolder holder = tester.addFilter(PutFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        holder.setInitParameter("delAllowed", "true");
        tester.setAttribute(ServletContext.TEMPDIR, workDir.getPath().toFile());
        // Bloody Windows does not allow file renaming
        if (!System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows"))
            holder.setInitParameter("putAtomic", "true");
        tester.start();
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        tester.stop();
    }

    @Test
    public void testHandlePut() throws Exception
    {
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        // test GET
        request.setMethod("GET");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setURI("/context/file.txt");
        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_NOT_FOUND, response.getStatus());

        // test PUT0
        request.setMethod("PUT");
        request.setURI("/context/file.txt");
        request.setHeader("Content-Type", "text/plain");
        String data0 = "Now is the time for all good men to come to the aid of the party";
        request.setContent(data0);
        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_CREATED, response.getStatus());

        Path file = root.resolve("file.txt");
        assertTrue(Files.exists(file));
        assertEquals(data0, IO.toString(file, UTF_8));

        // test GET1
        request.setMethod("GET");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setURI("/context/file.txt");
        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        assertEquals(data0, response.getContent());

        // test PUT1
        request.setMethod("PUT");
        request.setURI("/context/file.txt");
        request.setHeader("Content-Type", "text/plain");
        String data1 = "How Now BROWN COW!!!!";
        request.setContent(data1);
        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());

        file = root.resolve("file.txt");
        assertTrue(Files.exists(file));
        assertEquals(data1, IO.toString(file, UTF_8));

        // test PUT2
        request.setMethod("PUT");
        request.setURI("/context/file.txt");
        request.setHeader("Content-Type", "text/plain");
        String data2 = "Blah blah blah Blah blah";
        request.setContent(data2);
        String toSend = BufferUtil.toString(request.generate());
        URL url = new URL(tester.createConnector(true));
        Socket socket = new Socket(url.getHost(), url.getPort());
        OutputStream out = socket.getOutputStream();
        int l = toSend.length();
        out.write(toSend.substring(0, l - 10).getBytes());
        out.flush();
        Thread.sleep(100);
        out.write(toSend.substring(l - 10, l - 5).getBytes());
        out.flush();

        // loop until the resource is hidden (ie the PUT is starting to
        // read the file
        do
        {
            Thread.sleep(100);

            // test GET
            request.setMethod("GET");
            request.setVersion("HTTP/1.0");
            request.setHeader("Host", "tester");
            request.setURI("/context/file.txt");
            response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        }
        while (response.getStatus() == 200);
        assertEquals(HttpServletResponse.SC_NOT_FOUND, response.getStatus());

        out.write(toSend.substring(l - 5).getBytes());
        out.flush();
        String in = IO.toString(socket.getInputStream());

        request.setMethod("GET");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setURI("/context/file.txt");
        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        assertEquals(data2, response.getContent());
    }

    @Test
    public void testHandleDelete() throws Exception
    {
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        // test PUT1
        request.setMethod("PUT");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setURI("/context/file.txt");
        request.setHeader("Content-Type", "text/plain");
        String data1 = "How Now BROWN COW!!!!";
        request.setContent(data1);
        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_CREATED, response.getStatus());

        Path file = root.resolve("file.txt");
        assertTrue(Files.exists(file));
        assertEquals(data1, IO.toString(file, UTF_8));

        request.setMethod("DELETE");
        request.setURI("/context/file.txt");
        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_NO_CONTENT, response.getStatus());

        assertFalse(Files.exists(file));

        request.setMethod("DELETE");
        request.setURI("/context/file.txt");
        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
    }

    @Test
    public void testHandleMove() throws Exception
    {
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        // test PUT1
        request.setMethod("PUT");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setURI("/context/file.txt");
        request.setHeader("Content-Type", "text/plain");
        String data1 = "How Now BROWN COW!!!!";
        request.setContent(data1);
        response = HttpTester.parseResponse(tester.getResponses(request.generate()));

        assertEquals(HttpServletResponse.SC_CREATED, response.getStatus());

        Path file = root.resolve("file.txt");
        assertTrue(Files.exists(file));
        assertEquals(data1, IO.toString(file, UTF_8));

        request.setMethod("MOVE");
        request.setURI("/context/file.txt");
        request.setHeader("new-uri", "/context/blah.txt");
        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_NO_CONTENT, response.getStatus());

        assertFalse(Files.exists(file));

        Path nFile = root.resolve("blah.txt");
        assertTrue(Files.exists(nFile));
    }

    @Test
    public void testHandleOptions() throws Exception
    {
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        // test PUT1
        request.setMethod("OPTIONS");
        request.setVersion("HTTP/1.0");
        request.put("Host", "tester");
        request.setURI("/context/file.txt");
        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());

        Set<String> options = new HashSet<String>();
        String allow = response.get("Allow");
        options.addAll(StringUtil.csvSplit(null, allow, 0, allow.length()));
        assertThat("GET", is(in(options)));
        assertThat("POST", is(in(options)));
        assertThat("PUT", is(in(options)));
        assertThat("MOVE", is(in(options)));
    }
}
