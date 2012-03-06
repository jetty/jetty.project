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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

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

public class MultipartFilterTest
{
    private File _dir;
    private ServletTester tester;

    
    public static class TestServlet extends DumpServlet
    {

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            assertNotNull(req.getParameter("fileup"));
            assertNotNull(req.getParameter("fileup"+MultiPartFilter.CONTENT_TYPE_SUFFIX));
            assertEquals(req.getParameter("fileup"+MultiPartFilter.CONTENT_TYPE_SUFFIX), "application/octet-stream");
            
            super.doPost(req, resp);
        }
        
    }
    

    
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
        tester.addServlet(TestServlet.class, "/");
        FilterHolder multipartFilter = tester.addFilter(MultiPartFilter.class,"/*",FilterMapping.DEFAULT);
        multipartFilter.setInitParameter("deleteFiles", "true");
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
        request.setHeader("Content-Type","multipart/form-data; boundary=\""+boundary+"\"");
        
        
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
  

    @Test
    public void testEncodedPost() throws Exception
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
        "Content-Disposition: form-data; name=\"fileup\"; filename=\"Diplomsko Delo Lektorirano KON&#268;NA.doc\"\r\n"+
        "Content-Type: application/octet-stream\r\n\r\n"+
        "How now brown cow."+
        "\r\n--" + boundary + "--\r\n\r\n";
        
        request.setContent(content);
        
        response.parse(tester.getResponses(request.generate()));
        assertTrue(response.getMethod()==null);
        assertEquals(HttpServletResponse.SC_OK,response.getStatus());
        assertTrue(response.getContent().indexOf("brown cow")>=0);
    }

    /*
     * Test multipart with parts encoded in base64 (RFC1521 section 5)
     */
    @Test
    public void testPostWithContentTransferEncodingBase64() throws Exception {
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
        
        // part content is "How now brown cow." run through a base64 encoder
        String content = "--" + boundary + "\r\n"+
        "Content-Disposition: form-data; name=\"fileup\"; filename=\"test.upload\"\r\n"+
        "Content-Transfer-Encoding: base64\r\n"+
        "Content-Type: application/octet-stream\r\n\r\n"+
        "SG93IG5vdyBicm93biBjb3cuCg=="+
        "\r\n--" + boundary + "--\r\n\r\n";
        
        request.setContent(content);
        
        response.parse(tester.getResponses(request.generate()));
        assertTrue(response.getMethod()==null);
        assertEquals(HttpServletResponse.SC_OK,response.getStatus());
        assertTrue(response.getContent().indexOf("brown cow")>=0);
    }

    /*
     * Test multipart with parts encoded in quoted-printable (RFC1521 section 5)
     */
    @Test
    public void testPostWithContentTransferEncodingQuotedPrintable() throws Exception {
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
        
        /*
         * Part content is "How now brown cow." run through Apache Commons Codec
         * quoted printable encoding.
         */
        String content = "--" + boundary + "\r\n"+
        "Content-Disposition: form-data; name=\"fileup\"; filename=\"test.upload\"\r\n"+
        "Content-Transfer-Encoding: quoted-printable\r\n"+
        "Content-Type: application/octet-stream\r\n\r\n"+
        "=48=6F=77=20=6E=6F=77=20=62=72=6F=77=6E=20=63=6F=77=2E"+
        "\r\n--" + boundary + "--\r\n\r\n";
        
        request.setContent(content);
        
        response.parse(tester.getResponses(request.generate()));
        assertTrue(response.getMethod()==null);
        assertEquals(HttpServletResponse.SC_OK,response.getStatus());
        assertTrue(response.getContent().indexOf("brown cow")>=0);
    }

    /*
     * see the testParameterMap test
     *
     */
    public static class TestServletParameterMap extends DumpServlet
    {

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            assertEquals("How now brown cow.", req.getParameterMap().get("strupContent-Type: application/octet-stream"));
                   
            super.doPost(req, resp);
        }
        
    }
    
    /** 
     * Validate that the getParameterMap() call is correctly unencoding the parameters in the 
     * map that it returns.
     * @throws Exception
     */
    @Test
    public void testParameterMap() throws Exception
    {
        // generated and parsed test
        HttpTester request = new HttpTester();
        HttpTester response = new HttpTester();

        tester.addServlet(TestServletParameterMap.class,"/test2");
        
        // test GET
        request.setMethod("POST");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setURI("/context/test2");
        
        String boundary="XyXyXy";
        request.setHeader("Content-Type","multipart/form-data; boundary="+boundary);
        
        
        String content = "--" + boundary + "\r\n"+
        "Content-Disposition: form-data; name=\"fileup\"; filename=\"Diplomsko Delo Lektorirano KON&#268;NA.doc\"\r\n"+
        "Content-Type: application/octet-stream\r\n\r\n"+
        "How now brown cow."+
        "\r\n--" + boundary + "\r\n"+
        "Content-Disposition: form-data; name=\"strup\""+
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
        private static final long serialVersionUID = 201012011130L;

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
