//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.servlet;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.eclipse.jetty.http.DateGenerator;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.util.IO;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class DefaultServletTest
{
    @Rule
    public TestingDir testdir = new TestingDir();

    private Server server;
    private LocalConnector connector;
    private ServletContextHandler context;

    @Before
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

        server.start();
    }

    @After
    public void destroy() throws Exception
    {
        server.stop();
        server.join();
    }

    @Test
    public void testListingWithSession() throws Exception
    {
        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/*");
        defholder.setInitParameter("dirAllowed", "true");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("gzip", "false");

        testdir.ensureEmpty();

        /* create some content in the docroot */
        File resBase = testdir.getFile("docroot");
        assertTrue(resBase.mkdirs());
        assertTrue(new File(resBase, "one").mkdir());
        assertTrue(new File(resBase, "two").mkdir());
        assertTrue(new File(resBase, "three").mkdir());

        String resBasePath = resBase.getAbsolutePath();
        defholder.setInitParameter("resourceBase", resBasePath);

        StringBuffer req1 = new StringBuffer();
        req1.append("GET /context/;JSESSIONID=1234567890 HTTP/1.0\n\n");

        String response = connector.getResponses(req1.toString());

        assertResponseContains("/one/;JSESSIONID=1234567890", response);
        assertResponseContains("/two/;JSESSIONID=1234567890", response);
        assertResponseContains("/three/;JSESSIONID=1234567890", response);

        assertResponseNotContains("<script>", response);
    }

    @Test
    public void testListingXSS() throws Exception
    {
        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/*");
        defholder.setInitParameter("dirAllowed", "true");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("gzip", "false");

        testdir.ensureEmpty();

        /* create some content in the docroot */
        File resBase = testdir.getFile("docroot");
        FS.ensureDirExists(resBase);
        assertTrue(new File(resBase, "one").mkdir());
        assertTrue(new File(resBase, "two").mkdir());
        assertTrue(new File(resBase, "three").mkdir());
        if (!OS.IS_WINDOWS)
        {
            assertTrue("Creating dir 'f??r' (Might not work in Windows)", new File(resBase, "f??r").mkdir());
        }

        String resBasePath = resBase.getAbsolutePath();
        defholder.setInitParameter("resourceBase", resBasePath);

        StringBuffer req1 = new StringBuffer();
        /*
         * Intentionally bad request URI. Sending a non-encoded URI with typically encoded characters '<', '>', and
         * '"'.
         */
        req1.append("GET /context/;<script>window.alert(\"hi\");</script> HTTP/1.0\n");
        req1.append("\n");

        String response = connector.getResponses(req1.toString());

        assertResponseContains("/one/", response);
        assertResponseContains("/two/", response);
        assertResponseContains("/three/", response);
        if (!OS.IS_WINDOWS)
        {
            assertResponseContains("/f%3F%3Fr", response);
        }

        assertResponseNotContains("<script>", response);
    }

    @Test
    public void testListingProperUrlEncoding() throws Exception
    {
        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/*");
        defholder.setInitParameter("dirAllowed", "true");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("gzip", "false");

        testdir.ensureEmpty();

        /* create some content in the docroot */
        File resBase = testdir.getFile("docroot");
        assertTrue(resBase.mkdirs());
        File wackyDir = new File(resBase, "dir;"); // this should not be double-encoded.
        assertTrue(wackyDir.mkdirs());

        assertTrue(new File(wackyDir, "four").mkdir());
        assertTrue(new File(wackyDir, "five").mkdir());
        assertTrue(new File(wackyDir, "six").mkdir());

        /* At this point we have the following
         * testListingProperUrlEncoding/
         * `-- docroot
         *     `-- dir;
         *         |-- five
         *         |-- four
         *         `-- six
         */

        String resBasePath = resBase.getAbsolutePath();
        defholder.setInitParameter("resourceBase", resBasePath);

        // First send request in improper, unencoded way.
        String response = connector.getResponses("GET /context/dir;/ HTTP/1.0\r\n\r\n");

        assertResponseContains("HTTP/1.1 404 Not Found", response);

        // Now send request in proper, encoded format.
        response = connector.getResponses("GET /context/dir%3B/ HTTP/1.0\r\n\r\n");

        // Should not see double-encoded ";"
        // First encoding: ";" -> "%3b"
        // Second encoding: "%3B" -> "%253B" (BAD!)
        assertResponseNotContains("%253B", response);

        assertResponseContains("/dir%3B/", response);
        assertResponseContains("/dir%3B/four/", response);
        assertResponseContains("/dir%3B/five/", response);
        assertResponseContains("/dir%3B/six/", response);
    }

    @Test
    public void testListingContextBreakout() throws Exception
    {
        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("dirAllowed", "true");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("gzip", "false");
        defholder.setInitParameter("aliases", "true");

        testdir.ensureEmpty();

        /* create some content in the docroot */
        File resBase = testdir.getFile("docroot");
        assertTrue(resBase.mkdirs());

        File index = new File(resBase, "index.html");
        createFile(index, "<h1>Hello Index</h1>");

        File wackyDir = new File(resBase, "dir?");
        if (!OS.IS_WINDOWS)
        {
            FS.ensureDirExists(wackyDir);
        }

        wackyDir = new File(resBase, "dir;");
        assertTrue(wackyDir.mkdirs());

        /* create some content outside of the docroot */
        File sekret = testdir.getFile("sekret");
        assertTrue(sekret.mkdirs());
        File pass = new File(sekret, "pass");
        createFile(pass, "Sssh, you shouldn't be seeing this");

        /* At this point we have the following
         * testListingContextBreakout/
         * |-- docroot
         * |   |-- index.html
         * |   |-- dir?
         * |   |-- dir;
         * `-- sekret
         *     `-- pass
         */

        String resBasePath = resBase.getAbsolutePath();
        defholder.setInitParameter("resourceBase", resBasePath);

        String response;

        response = connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
        assertResponseContains("<h1>Hello Index</h1>", response);

        response = connector.getResponses("GET /context/dir?/ HTTP/1.0\r\n\r\n");
        assertResponseContains("404", response);

        if (!OS.IS_WINDOWS)
        {
            response = connector.getResponses("GET /context/dir%3F/ HTTP/1.0\r\n\r\n");
            assertResponseContains("Directory: /context/dir?/<", response);
        }
        else
            assertResponseContains("404", response);

        response = connector.getResponses("GET /context/index.html HTTP/1.0\r\n\r\n");
        assertResponseContains("Hello Index", response);

        response = connector.getResponses("GET /context/dir%3F/../index.html HTTP/1.0\r\n\r\n");
        assertResponseContains("Hello Index", response);

        response = connector.getResponses("GET /context/dir%3F/../../ HTTP/1.0\r\n\r\n");
        assertResponseNotContains("Directory: ", response);

        response = connector.getResponses("GET /context/dir%3F/../../sekret/pass HTTP/1.0\r\n\r\n");
        assertResponseNotContains("Sssh", response);

        response = connector.getResponses("GET /context/dir?/../../ HTTP/1.0\r\n\r\n");
        assertResponseNotContains("Directory: ", response);

        response = connector.getResponses("GET /context/dir?/../../sekret/pass HTTP/1.0\r\n\r\n");
        assertResponseNotContains("Sssh", response);

        response = connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
        assertResponseContains("<h1>Hello Index</h1>", response);

        response = connector.getResponses("GET /context/dir;/ HTTP/1.0\r\n\r\n");
        assertResponseContains("404", response);

        response = connector.getResponses("GET /context/dir%3B/ HTTP/1.0\r\n\r\n");
        assertResponseContains("Directory: /context/dir;/<", response);

        response = connector.getResponses("GET /context/index.html HTTP/1.0\r\n\r\n");
        assertResponseContains("Hello Index", response);

        response = connector.getResponses("GET /context/dir%3B/../index.html HTTP/1.0\r\n\r\n");
        assertResponseContains("Hello Index", response);

        response = connector.getResponses("GET /context/dir%3B/../../ HTTP/1.0\r\n\r\n");
        assertResponseNotContains("Directory: ", response);

        response = connector.getResponses("GET /context/dir%3B/../../sekret/pass HTTP/1.0\r\n\r\n");
        assertResponseNotContains("Sssh", response);

        response = connector.getResponses("GET /context/dir;/../../ HTTP/1.0\r\n\r\n");
        assertResponseNotContains("Directory: ", response);

        response = connector.getResponses("GET /context/dir;/../../sekret/pass HTTP/1.0\r\n\r\n");
        assertResponseNotContains("Sssh", response);
    }

    @Test
    public void testWelcome() throws Exception
    {
        testdir.ensureEmpty();
        File resBase = testdir.getFile("docroot");
        FS.ensureDirExists(resBase);
        File inde = new File(resBase, "index.htm");
        File index = new File(resBase, "index.html");

        String resBasePath = resBase.getAbsolutePath();

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("dirAllowed", "false");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("welcomeServlets", "false");
        defholder.setInitParameter("gzip", "false");
        defholder.setInitParameter("resourceBase", resBasePath);
        defholder.setInitParameter("maxCacheSize", "1024000");
        defholder.setInitParameter("maxCachedFileSize", "512000");
        defholder.setInitParameter("maxCachedFiles", "100");

        @SuppressWarnings("unused")
        ServletHolder jspholder = context.addServlet(NoJspServlet.class, "*.jsp");

        String response = connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
        assertResponseContains("403", response);

        createFile(index, "<h1>Hello Index</h1>");
        response = connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
        assertResponseContains("<h1>Hello Index</h1>", response);

        createFile(inde, "<h1>Hello Inde</h1>");
        response = connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
        assertResponseContains("<h1>Hello Index</h1>", response);

        assertTrue(index.delete());
        response = connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
        assertResponseContains("<h1>Hello Inde</h1>", response);

        assertTrue(inde.delete());
        response = connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
        assertResponseContains("403", response);
    }

    @Test
    public void testWelcomeServlet() throws Exception
    {
        testdir.ensureEmpty();
        File resBase = testdir.getFile("docroot");
        FS.ensureDirExists(resBase);
        File inde = new File(resBase, "index.htm");
        File index = new File(resBase, "index.html");

        String resBasePath = resBase.getAbsolutePath();

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("dirAllowed", "false");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("welcomeServlets", "true");
        defholder.setInitParameter("gzip", "false");
        defholder.setInitParameter("resourceBase", resBasePath);

        @SuppressWarnings("unused")
        ServletHolder jspholder = context.addServlet(NoJspServlet.class, "*.jsp");

        String response;

        response = connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
        assertResponseContains("JSP support not configured", response);

        createFile(index, "<h1>Hello Index</h1>");
        response = connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
        assertResponseContains("<h1>Hello Index</h1>", response);

        createFile(inde, "<h1>Hello Inde</h1>");
        response = connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
        assertResponseContains("<h1>Hello Index</h1>", response);

        // In Windows it's impossible to delete files that are somehow in use
        // Avoid to fail the test if we're on Windows
        if (!OS.IS_WINDOWS)
        {
            deleteFile(index);
            response = connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
            assertResponseContains("<h1>Hello Inde</h1>", response);

            deleteFile(inde);
            response = connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
            assertResponseContains("JSP support not configured", response);
        }
    }

    @Test
    public void testResourceBase() throws Exception
    {
        testdir.ensureEmpty();
        File resBase = testdir.getFile("docroot");
        FS.ensureDirExists(resBase);
        File foobar = new File(resBase, "foobar.txt");
        File link = new File(resBase, "link.txt");
        createFile(foobar, "Foo Bar");

        String resBasePath = resBase.getAbsolutePath();

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("resourceBase", resBasePath);
        defholder.setInitParameter("gzip", "false");

        String response;

        response = connector.getResponses("GET /context/foobar.txt HTTP/1.0\r\n\r\n");
        assertResponseContains("Foo Bar", response);

        if (!OS.IS_WINDOWS)
        {
            Files.createSymbolicLink(link.toPath(),foobar.toPath());
            response = connector.getResponses("GET /context/link.txt HTTP/1.0\r\n\r\n");
            assertResponseContains("404", response);
            
            context.addAliasCheck(new ContextHandler.ApproveAliases());
            
            response = connector.getResponses("GET /context/link.txt HTTP/1.0\r\n\r\n");
            assertResponseContains("Foo Bar", response);
        }
    }

    @Test
    public void testWelcomeExactServlet() throws Exception
    {
        testdir.ensureEmpty();
        File resBase = testdir.getFile("docroot");
        FS.ensureDirExists(resBase);
        File inde = new File(resBase, "index.htm");
        File index = new File(resBase, "index.html");

        String resBasePath = resBase.getAbsolutePath();

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("dirAllowed", "false");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("welcomeServlets", "exact");
        defholder.setInitParameter("gzip", "false");
        defholder.setInitParameter("resourceBase", resBasePath);

        ServletHolder jspholder = context.addServlet(NoJspServlet.class, "*.jsp");
        context.addServlet(jspholder, "/index.jsp");

        String response;

        response = connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
        assertResponseContains("JSP support not configured", response);

        createFile(index, "<h1>Hello Index</h1>");
        response = connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
        assertResponseContains("<h1>Hello Index</h1>", response);

        createFile(inde, "<h1>Hello Inde</h1>");
        response = connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
        assertResponseContains("<h1>Hello Index</h1>", response);

        // In Windows it's impossible to delete files that are somehow in use
        // Avoid to fail the test if we're on Windows
        if (!OS.IS_WINDOWS)
        {
            deleteFile(index);
            response = connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
            assertResponseContains("<h1>Hello Inde</h1>", response);

            deleteFile(inde);
            response = connector.getResponses("GET /context/ HTTP/1.0\r\n\r\n");
            assertResponseContains("JSP support not configured", response);
        }
    }

    @Test
    public void testRangeRequests() throws Exception
    {
        testdir.ensureEmpty();
        File resBase = testdir.getFile("docroot");
        FS.ensureDirExists(resBase);
        File data = new File(resBase, "data.txt");
        createFile(data, "01234567890123456789012345678901234567890123456789012345678901234567890123456789");
        String resBasePath = resBase.getAbsolutePath();

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("dirAllowed", "false");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("welcomeServlets", "false");
        defholder.setInitParameter("gzip", "false");
        defholder.setInitParameter("acceptRanges", "true");
        defholder.setInitParameter("resourceBase", resBasePath);

        String response = connector.getResponses("GET /context/data.txt HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n\r\n");
        assertResponseContains("200 OK", response);
        assertResponseContains("Accept-Ranges: bytes", response);



        response = connector.getResponses("GET /context/data.txt HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n"+
                "Range: bytes=0-9\r\n" +
                "\r\n");
        assertResponseContains("206 Partial", response);
        assertResponseContains("Content-Type: text/plain", response);
        assertResponseContains("Content-Length: 10", response);
        assertResponseContains("Content-Range: bytes 0-9/80", response);


        response = connector.getResponses("GET /context/data.txt HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n"+
                "Range: bytes=0-9,20-29,40-49\r\n" +
                "\r\n");
        int start = response.indexOf("--jetty");
        String body = response.substring(start);
        String boundary = body.substring(0, body.indexOf("\r\n"));
        assertResponseContains("206 Partial", response);
        assertResponseContains("Content-Type: multipart/byteranges; boundary=", response);
        assertResponseContains("Content-Range: bytes 0-9/80", response);
        assertResponseContains("Content-Range: bytes 20-29/80", response);
        assertResponseContains("Content-Length: " + body.length(), response);
        assertTrue(body.endsWith(boundary + "--\r\n"));

        response = connector.getResponses("GET /context/data.txt HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n"+
                "Range: bytes=0-9,20-29,40-49,70-79\r\n" +
                "\r\n");
        start = response.indexOf("--jetty");
        body = response.substring(start);
        boundary = body.substring(0, body.indexOf("\r\n"));
        assertResponseContains("206 Partial", response);
        assertResponseContains("Content-Type: multipart/byteranges; boundary=", response);
        assertResponseContains("Content-Range: bytes 0-9/80", response);
        assertResponseContains("Content-Range: bytes 20-29/80", response);
        assertResponseContains("Content-Range: bytes 70-79/80", response);
        assertResponseContains("Content-Length: " + body.length(), response);
        assertTrue(body.endsWith(boundary + "--\r\n"));

        response = connector.getResponses("GET /context/data.txt HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n"+
                "Range: bytes=0-9,20-29,40-49,60-60,70-79\r\n" +
                "\r\n");
        start = response.indexOf("--jetty");
        body = response.substring(start);
        boundary = body.substring(0, body.indexOf("\r\n"));
        assertResponseContains("206 Partial", response);
        assertResponseContains("Content-Type: multipart/byteranges; boundary=", response);
        assertResponseContains("Content-Range: bytes 0-9/80", response);
        assertResponseContains("Content-Range: bytes 20-29/80", response);
        assertResponseContains("Content-Range: bytes 60-60/80", response);
        assertResponseContains("Content-Range: bytes 70-79/80", response);
        assertResponseContains("Content-Length: " + body.length(), response);
        assertTrue(body.endsWith(boundary + "--\r\n"));

        //test a range request with a file with no suffix, therefore no mimetype

        File nofilesuffix = new File(resBase, "nofilesuffix");
        createFile(nofilesuffix, "01234567890123456789012345678901234567890123456789012345678901234567890123456789");

        response = connector.getResponses("GET /context/nofilesuffix HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n"+  
                "\r\n");
        assertResponseContains("200 OK", response);
        assertResponseContains("Accept-Ranges: bytes", response);



        response = connector.getResponses("GET /context/nofilesuffix HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n"+
                "Range: bytes=0-9\r\n" +
                "\r\n");
        assertResponseContains("206 Partial", response);
        assertResponseContains("Content-Length: 10", response);
        assertTrue(!response.contains("Content-Type:"));
        assertResponseContains("Content-Range: bytes 0-9/80", response);

        response = connector.getResponses("GET /context/nofilesuffix HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n"+     
                "Range: bytes=0-9,20-29,40-49\r\n" +
                "\r\n");
        start = response.indexOf("--jetty");
        body = response.substring(start);
        boundary = body.substring(0, body.indexOf("\r\n"));
        assertResponseContains("206 Partial", response);
        assertResponseContains("Content-Type: multipart/byteranges; boundary=", response);
        assertResponseContains("Content-Range: bytes 0-9/80", response);
        assertResponseContains("Content-Range: bytes 20-29/80", response);
        assertResponseContains("Content-Length: " + body.length(), response);
        assertTrue(body.endsWith(boundary + "--\r\n"));



        response = connector.getResponses("GET /context/nofilesuffix HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n"+    
                "Range: bytes=0-9,20-29,40-49,60-60,70-79\r\n" +
                "\r\n");
        start = response.indexOf("--jetty");
        body = response.substring(start);
        boundary = body.substring(0, body.indexOf("\r\n"));
        assertResponseContains("206 Partial", response);
        assertResponseContains("Content-Type: multipart/byteranges; boundary=", response);
        assertResponseContains("Content-Range: bytes 0-9/80", response);
        assertResponseContains("Content-Range: bytes 20-29/80", response);
        assertResponseContains("Content-Range: bytes 60-60/80", response);
        assertResponseContains("Content-Range: bytes 70-79/80", response);
        assertResponseContains("Content-Length: " + body.length(), response);
        assertTrue(body.endsWith(boundary + "--\r\n"));
    }




    @Test
    public void testFiltered() throws Exception
    {
        testdir.ensureEmpty();
        File resBase = testdir.getFile("docroot");
        FS.ensureDirExists(resBase);
        File file0 = new File(resBase, "data0.txt");
        createFile(file0, "Hello Text 0");

        String resBasePath = resBase.getAbsolutePath();

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("dirAllowed", "false");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("welcomeServlets", "false");
        defholder.setInitParameter("gzip", "false");
        defholder.setInitParameter("resourceBase", resBasePath);

        String response = connector.getResponses("GET /context/data0.txt HTTP/1.0\r\n\r\n");
        assertResponseContains("Content-Length: 12", response);
        assertResponseNotContains("Extra Info", response);

        context.addFilter(OutputFilter.class,"/*",EnumSet.of(DispatcherType.REQUEST));
        response = connector.getResponses("GET /context/data0.txt HTTP/1.0\r\n\r\n");
        assertResponseContains("Content-Length: 2", response); // 20 something long
        assertResponseContains("Extra Info", response);
        assertResponseNotContains("Content-Length: 12", response);

        context.getServletHandler().setFilterMappings(new FilterMapping[]{});
        context.getServletHandler().setFilters(new FilterHolder[]{});

        context.addFilter(WriterFilter.class,"/*",EnumSet.of(DispatcherType.REQUEST));
        response = connector.getResponses("GET /context/data0.txt HTTP/1.0\r\n\r\n");
        assertResponseContains("Content-Length: 2", response); // 20 something long
        assertResponseContains("Extra Info", response);
        assertResponseNotContains("Content-Length: 12", response);
    }


    @Test
    public void testGzip() throws Exception
    {
        testdir.ensureEmpty();
        File resBase = testdir.getFile("docroot");
        FS.ensureDirExists(resBase);
        File file0 = new File(resBase, "data0.txt");
        createFile(file0, "Hello Text 0");
        File file0gz = new File(resBase, "data0.txt.gz");
        createFile(file0gz, "fake gzip");

        String resBasePath = resBase.getAbsolutePath();

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("dirAllowed", "false");
        defholder.setInitParameter("redirectWelcome", "false");
        defholder.setInitParameter("welcomeServlets", "false");
        defholder.setInitParameter("gzip", "true");
        defholder.setInitParameter("resourceBase", resBasePath);

        String response = connector.getResponses("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\n\r\n");
        assertResponseContains("Content-Length: 12", response);
        assertResponseContains("Hello Text 0",response);
        assertResponseContains("Vary: Accept-Encoding",response);
        assertResponseNotContains("Content-Encoding: gzip",response);
        
        response = connector.getResponses("GET /context/data0.txt HTTP/1.0\r\nHost:localhost:8080\r\nAccept-Encoding:gzip\r\n\r\n");
        assertResponseContains("Content-Length: 9", response);
        assertResponseContains("fake gzip",response);
        assertResponseContains("Vary: Accept-Encoding",response);
        assertResponseContains("Content-Encoding: gzip",response);
        
    }



    @Test
    public void testIfModifiedSmall() throws Exception
    {
        testIfModified("Hello World");
    }
    
    @Test
    public void testIfModifiedLarge() throws Exception
    {
        testIfModified("Now is the time for all good men to come to the aid of the party");
    }

    public void testIfModified(String content) throws Exception
    {
        testdir.ensureEmpty();
        File resBase = testdir.getFile("docroot");
        FS.ensureDirExists(resBase);
        File file = new File(resBase, "file.txt");

        String resBasePath = resBase.getAbsolutePath();

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("resourceBase", resBasePath);
        defholder.setInitParameter("maxCacheSize", "4096");
        defholder.setInitParameter("maxCachedFileSize", "25");
        defholder.setInitParameter("maxCachedFiles", "100");

        String response = connector.getResponses("GET /context/file.txt HTTP/1.0\r\n\r\n");
        assertResponseContains("404", response);

        createFile(file, content);
        response = connector.getResponses("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\n\r\n");

        assertResponseContains("200", response);
        assertResponseContains("Last-Modified", response);
        String last_modified = getHeaderValue("Last-Modified",response);
        
        response = connector.getResponses("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\nIf-Modified-Since: "+last_modified+"\r\n\r\n");
        assertResponseContains("304", response);
        
        response = connector.getResponses("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\nIf-Modified-Since: "+DateGenerator.formatDate(System.currentTimeMillis()-10000)+"\r\n\r\n");
        assertResponseContains("200", response);
        
        response = connector.getResponses("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\nIf-Modified-Since: "+DateGenerator.formatDate(System.currentTimeMillis()+10000)+"\r\n\r\n");
        assertResponseContains("304", response);
        
        response = connector.getResponses("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\nIf-Unmodified-Since: "+DateGenerator.formatDate(System.currentTimeMillis()+10000)+"\r\n\r\n");
        assertResponseContains("200", response);
        
        response = connector.getResponses("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\nIf-Unmodified-Since: "+DateGenerator.formatDate(System.currentTimeMillis()-10000)+"\r\n\r\n");
        assertResponseContains("412", response);
    }

    @Test
    public void testIfETagSmall() throws Exception
    {
        testIfETag("Hello World");
    }
    
    @Test
    public void testIfETagLarge() throws Exception
    {
        testIfETag("Now is the time for all good men to come to the aid of the party");
    }

    public void testIfETag(String content) throws Exception
    {
        testdir.ensureEmpty();
        File resBase = testdir.getFile("docroot");
        FS.ensureDirExists(resBase);
        File file = new File(resBase, "file.txt");

        String resBasePath = resBase.getAbsolutePath();

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("resourceBase", resBasePath);
        defholder.setInitParameter("maxCacheSize", "4096");
        defholder.setInitParameter("maxCachedFileSize", "25");
        defholder.setInitParameter("maxCachedFiles", "100");
        defholder.setInitParameter("etags", "true");

        String response;

        createFile(file, content);
        response = connector.getResponses("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\n\r\n");

        assertResponseContains("200", response);
        assertResponseContains("ETag", response);
        String etag = getHeaderValue("ETag",response);
        
        response = connector.getResponses("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\nIf-None-Match: "+etag+"\r\n\r\n");
        assertResponseContains("304", response);
        
        response = connector.getResponses("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\nIf-None-Match: wibble,"+etag+",wobble\r\n\r\n");
        assertResponseContains("304", response);
        
        response = connector.getResponses("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\nIf-None-Match: wibble\r\n\r\n");
        assertResponseContains("200", response);
        
        response = connector.getResponses("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\nIf-None-Match: wibble, wobble\r\n\r\n");
        assertResponseContains("200", response);
        
        response = connector.getResponses("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\nIf-Match: "+etag+"\r\n\r\n");
        assertResponseContains("200", response);
        
        response = connector.getResponses("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\nIf-Match: wibble,"+etag+",wobble\r\n\r\n");
        assertResponseContains("200", response);
        
        response = connector.getResponses("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\nIf-Match: wibble\r\n\r\n");
        assertResponseContains("412", response);
        
        response = connector.getResponses("GET /context/file.txt HTTP/1.1\r\nHost:test\r\nConnection:close\r\nIf-Match: wibble, wobble\r\n\r\n");
        assertResponseContains("412", response);
        
    }
    
    public static class OutputFilter implements Filter
    {
        @Override
        public void init(FilterConfig filterConfig) throws ServletException
        {
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            response.getOutputStream().println("Extra Info");
            chain.doFilter(request, response);
        }

        @Override
        public void destroy()
        {
        }
    }

    public static class WriterFilter implements Filter
    {
        @Override
        public void init(FilterConfig filterConfig) throws ServletException
        {
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            response.getWriter().println("Extra Info");
            chain.doFilter(request, response);
        }

        @Override
        public void destroy()
        {
        }
    }

    private void createFile(File file, String str) throws IOException
    {
        FileOutputStream out = null;
        try
        {
            out = new FileOutputStream(file);
            out.write(str.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        finally
        {
            IO.close(out);
        }
    }

    private void assertResponseNotContains(String forbidden, String response)
    {
        Assert.assertThat(response,Matchers.not(Matchers.containsString(forbidden)));
    }

    private int assertResponseContains(String expected, String response)
    {
        Assert.assertThat(response,Matchers.containsString(expected));
        return response.indexOf(expected);
    }

    private void deleteFile(File file) throws IOException
    {
        if (OS.IS_WINDOWS)
        {
            // Windows doesn't seem to like to delete content that was recently created
            // Attempt a delete and if it fails, attempt a rename
            boolean deleted = file.delete();
            if (!deleted)
            {
                File deletedDir = MavenTestingUtils.getTargetFile(".deleted");
                FS.ensureDirExists(deletedDir);
                File dest = File.createTempFile(file.getName(), "deleted", deletedDir);
                boolean renamed = file.renameTo(dest);
                if (!renamed)
                    System.err.println("WARNING: unable to move file out of the way: " + file.getName());
            }
        }
        else
        {
            Assert.assertTrue("Deleting: " + file.getName(), file.delete());
        }
    }
    
    private String getHeaderValue(String header, String response)
    {
        Pattern pattern=Pattern.compile("[\\r\\n]"+header+"\\s*:\\s*(.*?)\\s*[\\r\\n]");
        Matcher matcher = pattern.matcher(response);
        if (matcher.find())
            return matcher.group(1);
        return null;
    }
}
