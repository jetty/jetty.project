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

package org.eclipse.jetty.servlets;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.Map;

import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletTester;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class MultipartFilterTest
{
    private File _dir;
    private ServletTester tester;
    FilterHolder multipartFilter;
    
    @SuppressWarnings("serial")
    public static class FilenameServlet extends TestServlet
    {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            assertThat(req.getAttribute("fileup"), notNullValue());
            super.doPost(req, resp);
        }
    }
    
    @SuppressWarnings("serial")
    public static class ParameterListServlet extends TestServlet
    {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.setContentType("text/plain");
            PrintWriter out = resp.getWriter();
            
            Enumeration<String> pnames = req.getParameterNames();
            while (pnames.hasMoreElements())
            {
                String pname = pnames.nextElement();
                Object param = req.getParameter(pname);
                out.printf("Parameter[%s] = ",pname);
                if (param == null)
                {
                    out.println(" <null>");
                }
                else
                {
                    out.printf("(%s) %s%n",param.getClass().getName(),param);
                }
            }
        }
    }

    @SuppressWarnings("serial")
    public static class BoundaryServlet extends TestServlet
    {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            //we have configured the multipart filter to always store to disk (file size threshold == 1)
            //but fileName has no filename param, so only the attribute should be set
            assertThat("getParameter('fileName')", req.getParameter("fileName"), nullValue());
            assertThat("getAttribute('fileName')", req.getAttribute("fileName"), notNullValue());
            
            File f = (File)req.getAttribute("fileName");
            assertThat("File exists", f.exists(), is(true));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            IO.copy(new FileInputStream(f), baos);
            assertEquals(getServletContext().getAttribute("fileName"), baos.toString());
            assertNull(req.getParameter("desc"));
            assertNotNull(req.getAttribute("desc"));
            baos = new ByteArrayOutputStream();
            IO.copy(new FileInputStream((File)req.getAttribute("desc")), baos);
            assertEquals(getServletContext().getAttribute("desc"), baos.toString());
            assertNull(req.getParameter("title"));
            assertNotNull(req.getAttribute("title"));
            baos = new ByteArrayOutputStream();
            IO.copy(new FileInputStream((File)req.getAttribute("title")), baos);
            assertEquals(getServletContext().getAttribute("title"), baos.toString());
            super.doPost(req, resp);
        }
    }
    
    @SuppressWarnings("serial")
    public static class TestServlet extends DumpServlet
    {

        @SuppressWarnings("deprecation")
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            String fileup = req.getParameter("fileup");
            assertThat("getParameter('fileup')",fileup,notNullValue());
            
            System.err.println("Fileup=" + req.getParameter("fileup"));
            
            String fileupType = req.getParameter("fileup" + MultiPartFilter.CONTENT_TYPE_SUFFIX);
            assertThat("req.getParameter('fileup'+CONTENT_TYPE_SUFFIX)",fileupType,is("application/octet-stream"));
            super.doPost(req, resp);
        }
    }

    @SuppressWarnings("deprecation")
    @Before
    public void setUp() throws Exception
    {
        Path tmpDir = MavenTestingUtils.getTargetTestingPath("testmultupart");
        FS.ensureEmpty(tmpDir);
        
        _dir = tmpDir.toFile();

        tester=new ServletTester("/context");
        tester.getContext().setResourceBase(tmpDir.toString());
        tester.getContext().addServlet(TestServlet.class, "/");
        tester.getContext().setAttribute("javax.servlet.context.tempdir", _dir);
        multipartFilter = tester.getContext().addFilter(MultiPartFilter.class,"/*", EnumSet.of(DispatcherType.REQUEST));
        multipartFilter.setInitParameter("deleteFiles", "true");
        multipartFilter.setInitParameter("fileOutputBuffer", "1"); //write a file if  there's more than 1 byte content
        tester.start();
    }

    @After
    public void tearDown() throws Exception
    {
        tester.stop();
        tester=null;
    }

    @Test
    public void testBadPost() throws Exception
    {
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

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


        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,response.getStatus());
    }


    @Test
    public void testPost() throws Exception
    {
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

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

        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_OK,response.getStatus());
        assertTrue(response.getContent().indexOf("brown cow")>=0);
    }

    @Test
    public void testEncodedPost() throws Exception
    {
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

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

        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_OK,response.getStatus());
        assertTrue(response.getContent().indexOf("brown cow")>=0);
    }
    
    @Test
    public void testBadlyEncodedFilename() throws Exception
    {
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;
        // test GET
        request.setMethod("POST");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setURI("/context/dump");
        
        String boundary="XyXyXy";
        request.setHeader("Content-Type","multipart/form-data; boundary="+boundary);
        
        
        String content = "--" + boundary + "\r\n"+
        "Content-Disposition: form-data; name=\"fileup\"; filename=\"Taken on Aug 22 \\ 2012.jpg\"\r\n"+
        "Content-Type: application/octet-stream\r\n\r\n"+
        "How now brown cow."+
        "\r\n--" + boundary + "--\r\n\r\n";
        
        request.setContent(content);
        
        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        
        //System.out.printf("Content: [%s]%n", response.getContent());
        assertThat(response.getStatus(), is(HttpServletResponse.SC_OK));
        assertThat(response.getContent(), containsString("Filename [Taken on Aug 22 \\ 2012.jpg]"));
        assertThat(response.getContent(), containsString("How now brown cow."));
    }
    
    @Test
    public void testBadlyEncodedMSFilename() throws Exception
    {
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;
        // test GET
        request.setMethod("POST");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setURI("/context/dump");
        
        String boundary="XyXyXy";
        request.setHeader("Content-Type","multipart/form-data; boundary="+boundary);
        
        
        String content = "--" + boundary + "\r\n"+
        "Content-Disposition: form-data; name=\"fileup\"; filename=\"c:\\this\\really\\is\\some\\path\\to\\a\\file.txt\"\r\n"+
        "Content-Type: application/octet-stream\r\n\r\n"+
        "How now brown cow."+
        "\r\n--" + boundary + "--\r\n\r\n";
        
        request.setContent(content);
        
        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        
        //System.out.printf("Content: [%s]%n", response.getContent());

        assertThat(response.getStatus(), is(HttpServletResponse.SC_OK));       
        assertThat(response.getContent(), containsString("Filename [c:\\this\\really\\is\\some\\path\\to\\a\\file.txt]"));
        assertThat(response.getContent(), containsString("How now brown cow.")); 
    }

    @Test
    public void testCorrectlyEncodedMSFilename() throws Exception
    {
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;
        // test GET
        request.setMethod("POST");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setURI("/context/dump");
        
        String boundary="XyXyXy";
        request.setHeader("Content-Type","multipart/form-data; boundary="+boundary);
        
        
        String content = "--" + boundary + "\r\n"+
        "Content-Disposition: form-data; name=\"fileup\"; filename=\"c:\\\\this\\\\really\\\\is\\\\some\\\\path\\\\to\\\\a\\\\file.txt\"\r\n"+
        "Content-Type: application/octet-stream\r\n\r\n"+
        "How now brown cow."+
        "\r\n--" + boundary + "--\r\n\r\n";
        
        request.setContent(content);
        
        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        
        //System.out.printf("Content: [%s]%n", response.getContent());
        assertThat(response.getStatus(), is(HttpServletResponse.SC_OK));        
        assertThat(response.getContent(), containsString("Filename [c:\\this\\really\\is\\some\\path\\to\\a\\file.txt]"));
        assertThat(response.getContent(), containsString("How now brown cow.")); 
    }

    
    /*
     * Test multipart with parts encoded in base64 (RFC1521 section 5)
     */
    @Test
    public void testPostWithContentTransferEncodingBase64() throws Exception {
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

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

        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_OK,response.getStatus());
        assertTrue(response.getContent().indexOf("brown cow")>=0);
    }

    /*
     * Test multipart with parts encoded in quoted-printable (RFC1521 section 5)
     */
    @Test
    public void testPostWithContentTransferEncodingQuotedPrintable() throws Exception
    {
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

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

        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_OK,response.getStatus());
        assertTrue(response.getContent().indexOf("brown cow")>=0);
    }
    
    
    @Test
    public void testNoBoundary() throws Exception
    {
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        tester.setAttribute("fileName", "abc");
        tester.setAttribute("desc", "123");
        tester.setAttribute("title", "ttt");
        request.setMethod("POST");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setURI("/context/dump");

        request.setHeader("Content-Type","multipart/form-data");

        // generated and parsed test
        String content = "--\r\n"+
        "Content-Disposition: form-data; name=\"fileName\"\r\n"+
        "Content-Type: text/plain; charset=US-ASCII\r\n"+
        "Content-Transfer-Encoding: 8bit\r\n"+
        "\r\n"+
        "abc\r\n"+
        "--\r\n"+
        "Content-Disposition: form-data; name=\"desc\"\r\n"+ 
        "Content-Type: text/plain; charset=US-ASCII\r\n"+ 
        "Content-Transfer-Encoding: 8bit\r\n"+
        "\r\n"+
        "123\r\n"+ 
        "--\r\n"+ 
        "Content-Disposition: form-data; name=\"title\"\r\n"+
        "Content-Type: text/plain; charset=US-ASCII\r\n"+
        "Content-Transfer-Encoding: 8bit\r\n"+ 
        "\r\n"+
        "ttt\r\n"+ 
        "--\r\n"+
        "Content-Disposition: form-data; name=\"fileup\"; filename=\"test.upload\"\r\n"+
        "Content-Type: application/octet-stream\r\n"+
        "Content-Transfer-Encoding: binary\r\n"+ 
        "\r\n"+
        "000\r\n"+ 
        "----\r\n";
        request.setContent(content);

        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_OK,response.getStatus());
    }
    
    
    @Test
    public void testLFOnlyRequest() throws Exception
    { 
        String boundary="XyXyXy";
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;
        
        
        tester.addServlet(BoundaryServlet.class,"/testb");
        tester.setAttribute("fileName", "abc");
        tester.setAttribute("desc", "123");
        tester.setAttribute("title", "ttt");
        request.setMethod("POST");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setURI("/context/testb");
        request.setHeader("Content-Type","multipart/form-data; boundary="+boundary);

        String content = "--XyXyXy\n"+
        "Content-Disposition: form-data; name=\"fileName\"\n"+
        "Content-Type: text/plain; charset=US-ASCII\n"+
        "Content-Transfer-Encoding: 8bit\n"+
        "\n"+
        "abc\n"+
        "--XyXyXy\n"+
        "Content-Disposition: form-data; name=\"desc\"\n"+ 
        "Content-Type: text/plain; charset=US-ASCII\n"+ 
        "Content-Transfer-Encoding: 8bit\n"+
        "\n"+
        "123\n"+ 
        "--XyXyXy\n"+ 
        "Content-Disposition: form-data; name=\"title\"\n"+
        "Content-Type: text/plain; charset=US-ASCII\n"+
        "Content-Transfer-Encoding: 8bit\n"+ 
        "\n"+
        "ttt\n"+ 
        "--XyXyXy\n"+
        "Content-Disposition: form-data; name=\"fileup\"; filename=\"test.upload\"\n"+
        "Content-Type: application/octet-stream\n"+
        "Content-Transfer-Encoding: binary\n"+ 
        "\n"+
        "000\n"+ 
        "--XyXyXy--\n";
        request.setContent(content);

        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_OK,response.getStatus()); 
    }
    
    
    @Test
    public void testCROnlyRequest() throws Exception
    { 
        String boundary="XyXyXy";
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;
        tester.addServlet(BoundaryServlet.class,"/testb");
        tester.setAttribute("fileName", "abc");
        tester.setAttribute("desc", "123");
        tester.setAttribute("title", "ttt");
        request.setMethod("POST");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setURI("/context/testb");
        request.setHeader("Content-Type","multipart/form-data; boundary="+boundary);

        String content = "--XyXyXy\r"+
        "Content-Disposition: form-data; name=\"fileName\"\r"+
        "Content-Type: text/plain; charset=US-ASCII\r"+
        "Content-Transfer-Encoding: 8bit\r"+
        "\r"+
        "abc\r"+
        "--XyXyXy\r"+
        "Content-Disposition: form-data; name=\"desc\"\r"+ 
        "Content-Type: text/plain; charset=US-ASCII\r"+ 
        "Content-Transfer-Encoding: 8bit\r"+
        "\r"+
        "123\r"+ 
        "--XyXyXy\r"+ 
        "Content-Disposition: form-data; name=\"title\"\r"+
        "Content-Type: text/plain; charset=US-ASCII\r"+
        "Content-Transfer-Encoding: 8bit\r"+ 
        "\r"+
        "ttt\r"+ 
        "--XyXyXy\r"+
        "Content-Disposition: form-data; name=\"fileup\"; filename=\"test.upload\"\r"+
        "Content-Type: application/octet-stream\r"+
        "Content-Transfer-Encoding: binary\r"+ 
        "\r"+
        "000\r"+ 
        "--XyXyXy--\r";
        request.setContent(content);

        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_OK,response.getStatus()); 
    }
    
    
    @Test
    public void testCROnlyWithEmbeddedLFRequest() throws Exception
    { 
        String boundary="XyXyXy";
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;
        tester.addServlet(BoundaryServlet.class,"/testb");
        tester.setAttribute("fileName", "\nabc\n");
        tester.setAttribute("desc", "\n123\n");
        tester.setAttribute("title", "\nttt\n");
        request.setMethod("POST");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setURI("/context/testb");
        request.setHeader("Content-Type","multipart/form-data; boundary="+boundary);

        String content = "--XyXyXy\r"+
        "Content-Disposition: form-data; name=\"fileName\"\r"+
        "Content-Type: text/plain; charset=US-ASCII\r"+
        "Content-Transfer-Encoding: 8bit\r"+
        "\r"+
        "\nabc\n"+
        "\r"+
        "--XyXyXy\r"+
        "Content-Disposition: form-data; name=\"desc\"\r"+ 
        "Content-Type: text/plain; charset=US-ASCII\r"+ 
        "Content-Transfer-Encoding: 8bit\r"+
        "\r"+
        "\n123\n"+ 
        "\r"+
        "--XyXyXy\r"+ 
        "Content-Disposition: form-data; name=\"title\"\r"+
        "Content-Type: text/plain; charset=US-ASCII\r"+
        "Content-Transfer-Encoding: 8bit\r"+ 
        "\r"+
        "\nttt\n"+ 
        "\r"+
        "--XyXyXy\r"+
        "Content-Disposition: form-data; name=\"fileup\"; filename=\"test.upload\"\r"+
        "Content-Type: application/octet-stream\r"+
        "Content-Transfer-Encoding: binary\r"+ 
        "\r"+
        "000\r"+ 
        "--XyXyXy--\r";
        request.setContent(content);

        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_OK,response.getStatus()); 
    }

    @Test
    public void testNoBody()
    throws Exception
    {
        String boundary="XyXyXy";
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;
        
        request.setMethod("POST");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setURI("/context/dump");
        request.setHeader("Content-Type","multipart/form-data; boundary="+boundary);
        
        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getStatus());
        assertTrue(response.getContent().indexOf("Missing content")>=0);
    }

    @Test
    public void testWhitespaceBodyWithCRLF()
    throws Exception
    {
        String whitespace = "              \n\n\n\r\n\r\n\r\n\r\n";

        String boundary="XyXyXy";
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;
        request.setMethod("POST");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setURI("/context/dump");
        request.setHeader("Content-Type","multipart/form-data; boundary="+boundary);
        request.setContent(whitespace);
        
        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getStatus());
        assertTrue(response.getContent().indexOf("Missing initial")>=0);
    }
    
  
    @Test
    public void testWhitespaceBody()
    throws Exception
    {
        String whitespace = " ";

        String boundary="XyXyXy";
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;
        request.setMethod("POST");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setURI("/context/dump");
        request.setHeader("Content-Type","multipart/form-data; boundary="+boundary);
        request.setContent(whitespace);
        
        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getStatus());
        assertTrue(response.getContent().indexOf("Missing initial")>=0);
    }

    @Test
    public void testLeadingWhitespaceBodyWithCRLF()
    throws Exception
    {
        String boundary = "AaB03x";

        String body = "              \n\n\n\r\n\r\n\r\n\r\n"+
        "--AaB03x\r\n"+
        "content-disposition: form-data; name=\"field1\"\r\n"+
        "\r\n"+
        "Joe Blow\r\n"+
        "--AaB03x\r\n"+
        "Content-Disposition: form-data; name=\"fileup\"; filename=\"test.upload\"\r\n"+
        "Content-Type: application/octet-stream\r\n"+
        "\r\n" +
        "aaaa,bbbbb"+"\r\n" +
        "--AaB03x--\r\n";

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;
        request.setMethod("POST");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setURI("/context/dump");
        request.setHeader("Content-Type","multipart/form-data; boundary="+boundary);
        request.setContent(body);

        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        assertTrue(response.getContent().contains("aaaa,bbbbb"));
    }
    @Test
    public void testLeadingWhitespaceBodyWithoutCRLF()
    throws Exception
    {
        String boundary = "AaB03x";

        String body = "              "+
        "--AaB03x\r\n"+
        "content-disposition: form-data; name=\"field1\"\r\n"+
        "\r\n"+
        "Joe Blow\r\n"+
        "--AaB03x\r\n"+
        "Content-Disposition: form-data; name=\"fileup\"; filename=\"test.upload\"\r\n"+
        "Content-Type: application/octet-stream\r\n"+
        "\r\n" +
        "aaaa,bbbbb"+"\r\n" +
        "--AaB03x--\r\n";

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;
        request.setMethod("POST");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setURI("/context/dump");
        request.setHeader("Content-Type","multipart/form-data; boundary="+boundary);
        request.setContent(body);

        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        assertTrue(response.getContent().contains("aaaa,bbbbb"));
    }
    
    public void testContentTypeWithCharSet() throws Exception
    {
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        // test GET
        request.setMethod("POST");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setURI("/context/dump");

        String boundary="XyXyXy";
        request.setHeader("Content-Type","multipart/form-data; boundary=\""+boundary+"\"; charset=ISO-8859-1");


        String content = "--" + boundary + "\r\n"+
        "Content-Disposition: form-data; name=\"fileup\"; filename=\"test.upload\"\r\n"+
        "Content-Type: application/octet-stream\r\n\r\n"+
        "How now brown cow."+
        "\r\n--" + boundary + "--\r\n\r\n";

        request.setContent(content);

        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_OK,response.getStatus());
        assertTrue(response.getContent().indexOf("brown cow")>=0);
    }
    
    
    @Test
    public void testBufferOverflowNoCRLF () throws Exception
    {
        String boundary="XyXyXy";
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;
        tester.addServlet(BoundaryServlet.class,"/testb");
        tester.setAttribute("fileName", "abc");
        tester.setAttribute("desc", "123");
        tester.setAttribute("title", "ttt");
        request.setMethod("POST");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setURI("/context/testb");
        request.setHeader("Content-Type","multipart/form-data; boundary="+boundary);

        String content = "--XyXyXy";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(content.getBytes());

        for (int i=0; i< 8500; i++) //create content that will overrun default buffer size of BufferedInputStream
        {
            baos.write('a');
        }
        request.setContent(baos.toString());

        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertTrue(response.getContent().contains("Buffer size exceeded"));
        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getStatus());
    }

    @SuppressWarnings("serial")
    public static class TestServletParameterMap extends DumpServlet
    {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            String[] content = req.getParameterMap().get("\"strup\"Content-Type: application/octet-stream");           
            assertThat (content[0], containsString("How now brown cow."));
            super.doPost(req, resp);
        }
    }
    
    /**
     * Validate that the getParameterMap() call is correctly unencoding the parameters in the
     * map that it returns.
     * @throws Exception on test failure
     */
    @Test
    public void testParameterMap() throws Exception
    {
        tester.addServlet(TestServletParameterMap.class,"/test2");

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

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
        "\r\n--" + boundary + "\r\n"+
        "Content-Disposition: form-data; name=\"strup\""+ // FIXME: this is missing a "\r\n"??
        "Content-Type: application/octet-stream\r\n\r\n"+
        "How now brown cow."+
        "\r\n--" + boundary + "--\r\n\r\n";

        request.setContent(content);

        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_OK,response.getStatus());
        assertTrue(response.getContent().indexOf("brown cow")>=0);
    }
    
    /**
     * Validate that the uploaded file can be accessed on the name it was given
     * @throws Exception on test failure
     */
    @Test
    @Ignore("Fails for Bug #486394")
    public void testFileUpload_AccessViaFilename() throws Exception
    {
        tester.addServlet(ParameterListServlet.class,"/paramlist");
        
        multipartFilter.setInitParameter("fileOutputBuffer", "1");
        multipartFilter.setInitParameter("deleteFiles", "false");
        multipartFilter.setInitParameter("writeFilesWithFilenames", "true");

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        // test GET
        request.setMethod("POST");
        request.setURI("/context/paramlist");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host","tester");
        request.setHeader("Connection","close");
        String boundary="XyXyXy";
        request.setHeader("Content-Type","multipart/form-data; boundary=" + boundary);

        StringBuilder content = new StringBuilder();
        content.append("--").append(boundary).append("\r\n");
        content.append("Content-Disposition: form-data; name=\"file\"; filename=\"tiny.dat\"\r\n");
        content.append("Content-Type: application/octet-stream\r\n");
        content.append("\r\n");
        content.append("How now brown cow.\r\n");
        content.append("--").append(boundary).append("--\r\n");
        content.append("\r\n");
        
        request.setContent(content.toString());

        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertThat("Response status", response.getStatus(), is(HttpServletResponse.SC_OK));
        assertEquals(HttpServletResponse.SC_OK,response.getStatus());
        
        String contents = assertUploadedFileExists("tiny.dat");
        assertThat("contents", contents, containsString("How now brown cow."));
    }
    
    /**
     * Validate that the two upload files, with the same name="" on two different parts,
     * can be accessed with the filename="" portions.
     * @throws Exception on test failure
     */
    @Test
    @Ignore("Fails for Bug #486394")
    public void testTwoFileUploads_AccessViaFilename() throws Exception
    {
        tester.addServlet(ParameterListServlet.class,"/paramlist");
        
        multipartFilter.setInitParameter("fileOutputBuffer", "1");
        multipartFilter.setInitParameter("deleteFiles", "false");
        multipartFilter.setInitParameter("writeFilesWithFilenames", "true");

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        // test GET
        request.setMethod("POST");
        request.setURI("/context/paramlist");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host","tester");
        request.setHeader("Connection","close");

        String boundary="XyXyXy";
        request.setHeader("Content-Type","multipart/form-data; boundary=" + boundary);

        StringBuilder content = new StringBuilder();
        content.append("--").append(boundary).append("\r\n");
        content.append("Content-Disposition: form-data; name=\"same\"; filename=\"crocodile.dat\"\r\n");
        content.append("Content-Type: application/octet-stream\r\n");
        content.append("\r\n");
        content.append("See ya later, aligator.\r\n");
        content.append("--").append(boundary).append("\r\n");
        content.append("Content-Disposition: form-data; name=\"same\"; filename=\"aligator.dat\"\r\n");
        content.append("Content-Type: application/octet-stream\r\n");
        content.append("\r\n");
        content.append("In a while, crocodile.\r\n");
        content.append("--").append(boundary).append("--\r\n");
        content.append("\r\n");
        
        request.setContent(content.toString());

        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertThat("Response status", response.getStatus(), is(HttpServletResponse.SC_OK));
        assertEquals(HttpServletResponse.SC_OK,response.getStatus());
        
        String contents = assertUploadedFileExists("crocodile.dat");
        assertThat("contents", contents, containsString("See ya later, aligator."));
        
        contents = assertUploadedFileExists("aligator.dat");
        assertThat("contents", contents, containsString("In a while, crocodile."));
    }
    
    private String assertUploadedFileExists(String filename) throws IOException
    {
        File uploadedFile = new File(_dir,filename);
        assertThat("Uploaded File[" + uploadedFile + "].exists",uploadedFile.exists(),is(true));
        
        try (Reader reader = new FileReader(uploadedFile))
        {
            return IO.toString(reader);
        }
    }

    public static class TestServletCharSet extends HttpServlet
    {

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            //test that the multipart content bytes were converted correctly from their charset to unicode
            String content = (String)req.getParameter("ttt");
            assertNotNull(content);
            assertEquals("ttt\u01FCzzz",content);       
            assertEquals("application/octet-stream; charset=UTF-8",req.getParameter("ttt"+MultiPartFilter.CONTENT_TYPE_SUFFIX));
                  
            
            //test that the parameter map retrieves values as String[]
            Map map = req.getParameterMap();
            Object o = map.get("ttt");
            assertTrue(o.getClass().isArray());
            super.doPost(req, resp);
        } 
    }
    
    
    @Test
    public void testWithCharSet()
    throws Exception
    {
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;
        tester.addServlet(TestServletCharSet.class,"/test3");
        
        // test GET
        request.setMethod("POST");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setURI("/context/test3");
        
        String boundary="XyXyXy";
        request.setHeader("Content-Type","multipart/form-data; boundary="+boundary);
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        baos.write(("--" + boundary + "\r\n"+
                "Content-Disposition: form-data; name=\"ttt\"\r\n"+
                "Content-Type: application/octet-stream; charset=UTF-8\r\n\r\n").getBytes());
        baos.write("ttt\u01FCzzz".getBytes(StandardCharsets.UTF_8));
        baos.write(("\r\n--" + boundary + "--\r\n\r\n").getBytes());
  
        
        request.setContent(baos.toByteArray());   
        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
    }

    
    @Test
    public void testFilesWithFilenames ()
            throws Exception
    {       
        multipartFilter.setInitParameter("fileOutputBuffer", "0");
        multipartFilter.setInitParameter("writeFilesWithFilenames", "true");
        
        
        String boundary="XyXyXy";
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;
        tester.addServlet(FilenameServlet.class,"/testf");
        // test GET
        request.setMethod("POST");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setURI("/context/testf");
        request.setHeader("Content-Type","multipart/form-data; boundary="+boundary);

        String content = "--XyXyXy\r"+
        "Content-Disposition: form-data; name=\"fileName\"\r"+
        "Content-Type: text/plain; charset=US-ASCII\r"+
        "Content-Transfer-Encoding: 8bit\r"+
        "\r"+
        "abc\r"+
        "--XyXyXy\r"+
        "Content-Disposition: form-data; name=\"desc\"\r"+ 
        "Content-Type: text/plain; charset=US-ASCII\r"+ 
        "Content-Transfer-Encoding: 8bit\r"+
        "\r"+
        "123\r"+ 
        "--XyXyXy\r"+ 
        "Content-Disposition: form-data; name=\"title\"\r"+
        "Content-Type: text/plain; charset=US-ASCII\r"+
        "Content-Transfer-Encoding: 8bit\r"+ 
        "\r"+
        "ttt\r"+ 
        "--XyXyXy\r"+
        "Content-Disposition: form-data; name=\"fileup\"; filename=\"test.upload\"\r"+
        "Content-Type: application/octet-stream\r"+
        "Content-Transfer-Encoding: binary\r"+ 
        "\r"+
        "000\r"+ 
        "--XyXyXy--\r";
        request.setContent(content);

        response = HttpTester.parseResponse(tester.getResponses(request.generate()));
        assertEquals(HttpServletResponse.SC_OK,response.getStatus()); 
        assertTrue(response.getContent().indexOf("000")>=0);
    }
    
    @SuppressWarnings("serial")
    public static class DumpServlet extends HttpServlet
    {
        /**
         * @see javax.servlet.http.HttpServlet#doPost(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
         */
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            FileInputStream in = null;
            try {
                File file = (File)req.getAttribute("fileup");
                in = new FileInputStream(file);
                
                PrintWriter out = resp.getWriter();
                out.printf("Filename [%s]\r\n", req.getParameter("fileup"));
                out.println(IO.toString(in));
            } finally {
                IO.close(in);
            }
        }
    }
}
