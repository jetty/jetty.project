// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.servlets;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.util.Enumeration;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.testing.HttpTester;
import org.eclipse.jetty.testing.ServletTester;
import org.eclipse.jetty.util.IO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MultipartFilterTest
{
    private File _dir;
    private ServletTester tester;

    @Before
    public void setUp() throws Exception
    {
        _dir = File.createTempFile("testmultupart",null);
        assertTrue(_dir.delete());
        assertTrue(_dir.mkdir());
        _dir.deleteOnExit();
        assertTrue(_dir.isDirectory());

        tester=new ServletTester();
        tester.setContextPath("/context");
        tester.setResourceBase(_dir.getCanonicalPath());
        tester.addServlet(DumpServlet.class, "/");
        tester.addFilter(MultiPartFilter.class,"/*",FilterMapping.DEFAULT);
        tester.start();
    }

    @After
    public void tearDown() throws Exception
    {
        tester.stop();
    }

    @Test
    public void testBadPost() throws Exception
    {
        // generated and parsed test
        HttpTester request = new HttpTester();
        HttpTester response = new HttpTester();

        // test GET
        request.setMethod("POST");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setURI("/context/dump");
        
        String boundary="XyXyXy";
        request.setHeader("Content-Type","multipart/form-data; boundary="+boundary);
        
        
        String content = "--" + boundary + "\r\n"+
        "Content-Disposition: form-data; name=\"fileup\"; filename=\"test.upload\"\r\n"+
        "Content-Type: application/octet-stream\r\n\r\n"+
        "How now brown cow."+
        "\r\n--" + boundary + "-\r\n\r\n";
        
        request.setContent(content);
        
        
        response.parse(tester.getResponses(request.generate()));
        assertTrue(response.getMethod()==null);
        assertEquals(HttpServletResponse.SC_OK,response.getStatus());
    }

    @Test
    public void testPost() throws Exception
    {
        // generated and parsed test
        HttpTester request = new HttpTester();
        HttpTester response = new HttpTester();

        // test GET
        request.setMethod("POST");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setURI("/context/dump");
        
        String boundary="XyXyXy";
        request.setHeader("Content-Type","multipart/form-data; boundary="+boundary);
        
        
        String content = "--" + boundary + "\r\n"+
        "Content-Disposition: form-data; name=\"fileup\"; filename=\"test.upload\"\r\n"+
        "Content-Type: application/octet-stream\r\n\r\n"+
        "How now brown cow."+
        "\r\n--" + boundary + "--\r\n\r\n";
        
        request.setContent(content);
        
        response.parse(tester.getResponses(request.generate()));
        assertTrue(response.getMethod()==null);
        assertEquals(HttpServletResponse.SC_OK,response.getStatus());
        assertTrue(response.getContent().indexOf("brown cow")>=0);
    }

    public static class DumpServlet extends HttpServlet
    {
        /* ------------------------------------------------------------ */
        /**
         * @see javax.servlet.http.HttpServlet#doPost(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
         */
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.getWriter().println((IO.toString(new FileInputStream((File)req.getAttribute("fileup")))));
        }
        
    }
}
