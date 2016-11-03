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

package org.eclipse.jetty.servlet;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
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

public class DefaultServletRangesTest
{
    public static final String DATA = "01234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWZYZ!@#$%^&*()_+/.,[]";
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


        testdir.ensureEmpty();
        File resBase = testdir.getFile("docroot");
        FS.ensureDirExists(resBase);
        File data = new File(resBase, "data.txt");
        createFile(data, DATA);
        String resBasePath = resBase.getAbsolutePath();

        ServletHolder defholder = context.addServlet(DefaultServlet.class, "/");
        defholder.setInitParameter("acceptRanges", "true");
        defholder.setInitParameter("resourceBase", resBasePath);

        server.start();
    }

    @After
    public void destroy() throws Exception
    {
        server.stop();
        server.join();
    }

    @Test
    public void testNoRangeRequests() throws Exception
    {
        String response;

        response= connector.getResponses(
                "GET /context/data.txt HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n"+
                        "\r\n");
        assertResponseContains("200 OK", response);
        assertResponseContains("Accept-Ranges: bytes", response);
        assertResponseContains(DATA,response);
    }

    @Test
    public void testPrefixRangeRequests() throws Exception
    {
        String response;

        response = connector.getResponses(
                "GET /context/data.txt HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n"+
                        "Range: bytes=0-9\r\n" +
                        "\r\n");
        assertResponseContains("206 Partial", response);
        assertResponseContains("Content-Type: text/plain", response);
        assertResponseContains("Content-Range: bytes 0-9/80", response);
        assertResponseContains(DATA.substring(0,10), response);
    }

    @Test
    public void testSingleRangeRequests() throws Exception
    {
        String response;

        response = connector.getResponses(
                "GET /context/data.txt HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n"+
                        "Range: bytes=3-9\r\n" +
                        "\r\n");
        assertResponseContains("206 Partial", response);
        assertResponseContains("Content-Type: text/plain", response);
        assertResponseContains("Content-Range: bytes 3-9/80", response);
        assertResponseContains(DATA.substring(3,10), response);
    }

    @Test
    public void testMultipleRangeRequests() throws Exception
    {
        String response;
        response = connector.getResponses(
                "GET /context/data.txt HTTP/1.1\r\n" +
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
        assertResponseContains("Content-Range: bytes 40-49/80", response);
        assertResponseContains(DATA.substring(0,10), response);
        assertResponseContains(DATA.substring(20,30), response);
        assertResponseContains(DATA.substring(40,50), response);
        assertTrue(body.endsWith(boundary + "--\r\n"));

    }

    @Test
    public void testOpenEndRange() throws Exception
    {
        String response;
        response = connector.getResponses(
                "GET /context/data.txt HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n"+
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
        response = connector.getResponses(
                "GET /context/data.txt HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n"+
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
        response = connector.getResponses(
                "GET /context/data.txt HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n"+
                "Range: bytes=100-110\r\n" +
                "\r\n");
        assertResponseContains("416 Range Not Satisfiable", response);
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
}
