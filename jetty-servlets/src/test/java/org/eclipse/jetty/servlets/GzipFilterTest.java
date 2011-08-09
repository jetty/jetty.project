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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.gzip.GzipResponseWrapper;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.testing.HttpTester;
import org.eclipse.jetty.testing.ServletTester;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.util.IO;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class GzipFilterTest
{
    public static String __content;

    static
    {
        // The size of content must be greater then
        // buffer size in GzipResponseWrapper class. 
        StringBuilder builder = new StringBuilder();
        do
        {
            builder.append("Lorem ipsum dolor sit amet, consectetur adipiscing elit. In quis felis nunc. ");
            builder.append("Quisque suscipit mauris et ante auctor ornare rhoncus lacus aliquet. Pellentesque ");
            builder.append("habitant morbi tristique senectus et netus et malesuada fames ac turpis egestas. ");
            builder.append("Vestibulum sit amet felis augue, vel convallis dolor. Cras accumsan vehicula diam ");
            builder.append("at faucibus. Etiam in urna turpis, sed congue mi. Morbi et lorem eros. Donec vulputate ");
            builder.append("velit in risus suscipit lobortis. Aliquam id urna orci, nec sollicitudin ipsum. ");
            builder.append("Cras a orci turpis. Donec suscipit vulputate cursus. Mauris nunc tellus, fermentum ");
            builder.append("eu auctor ut, mollis at diam. Quisque porttitor ultrices metus, vitae tincidunt massa ");
            builder.append("sollicitudin a. Vivamus porttitor libero eget purus hendrerit cursus. Integer aliquam ");
            builder.append("consequat mauris quis luctus. Cras enim nibh, dignissim eu faucibus ac, mollis nec neque. ");
            builder.append("Aliquam purus mauris, consectetur nec convallis lacinia, porta sed ante. Suspendisse ");
            builder.append("et cursus magna. Donec orci enim, molestie a lobortis eu, imperdiet vitae neque.");
        }
        while (builder.length() < GzipResponseWrapper.DEFAULT_BUFFER_SIZE);
            
        __content = builder.toString();
    }

    @Rule
    public TestingDir testdir = new TestingDir();

    protected ServletTester tester;

    @Before
    public void setUp() throws Exception
    {
        testdir.ensureEmpty();

        File testFile = testdir.getFile("file.txt");
        BufferedOutputStream testOut = new BufferedOutputStream(new FileOutputStream(testFile));
        ByteArrayInputStream testIn = new ByteArrayInputStream(__content.getBytes("ISO8859_1"));
        IO.copy(testIn,testOut);
        testOut.close();
        
        testFile = testdir.getFile("file.mp3");
        testOut = new BufferedOutputStream(new FileOutputStream(testFile));
        testIn = new ByteArrayInputStream(__content.getBytes("ISO8859_1"));
        IO.copy(testIn,testOut);
        testOut.close();

        tester=new ServletTester();
        tester.setContextPath("/context");
        tester.setResourceBase(testdir.getDir().getCanonicalPath());
        tester.addServlet(getServletClass(), "/");
        FilterHolder holder = tester.addFilter(GzipFilter.class,"/*",0);
        holder.setInitParameter("mimeTypes","text/plain");
        tester.start();
    }

    @After
    public void tearDown() throws Exception
    {
        tester.stop();
        IO.delete(testdir.getDir());
    }
    
    public Class<?> getServletClass()
    {
        return org.eclipse.jetty.servlet.DefaultServlet.class;
    }
    
    @Test
    public void testGzip() throws Exception
    {
        // generated and parsed test
        HttpTester request = new HttpTester();
        HttpTester response = new HttpTester();

        request.setMethod("GET");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setHeader("accept-encoding","gzip");
        request.setURI("/context/file.txt");
        
        ByteArrayBuffer reqsBuff = new ByteArrayBuffer(request.generate().getBytes());
        ByteArrayBuffer respBuff = tester.getResponses(reqsBuff);
        response.parse(respBuff.asArray());
                
        assertTrue(response.getMethod()==null);
        assertNotNull("Content-Length header is missing", response.getHeader("Content-Length"));
        assertTrue(response.getHeader("Content-Encoding").equalsIgnoreCase("gzip"));
        assertEquals(HttpServletResponse.SC_OK,response.getStatus());
        
        InputStream testIn = new GZIPInputStream(new ByteArrayInputStream(response.getContentBytes()));
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();
        IO.copy(testIn,testOut);
        
        assertEquals(__content, testOut.toString("ISO8859_1"));
    }

    @Test
    public void testNotGzip() throws Exception
    {
        // generated and parsed test
        HttpTester request = new HttpTester();
        HttpTester response = new HttpTester();

        request.setMethod("GET");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setHeader("accept-encoding","gzip");
        request.setURI("/context/file.mp3");
        
        ByteArrayBuffer reqsBuff = new ByteArrayBuffer(request.generate().getBytes());
        ByteArrayBuffer respBuff = tester.getResponses(reqsBuff);
        response.parse(respBuff.asArray());
                
        assertTrue(response.getMethod()==null);
        assertNotNull("Content-Length header is missing", response.getHeader("Content-Length"));
        assertEquals(__content.getBytes().length, Integer.parseInt(response.getHeader("Content-Length")));
        assertEquals(HttpServletResponse.SC_OK,response.getStatus());
        
        InputStream testIn = new ByteArrayInputStream(response.getContentBytes());
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();
        IO.copy(testIn,testOut);
        
        assertEquals(__content, testOut.toString("ISO8859_1"));
    }
}
