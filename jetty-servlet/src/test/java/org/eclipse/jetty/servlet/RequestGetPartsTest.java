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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;

import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class RequestGetPartsTest
{
    @SuppressWarnings("serial")
    public static class DumpPartInfoServlet extends HttpServlet
    {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.setContentType("text/plain");
            PrintWriter out = resp.getWriter();
            
            for(Part part: req.getParts())
            {
                out.printf("Got part: name=%s, size=%,d, filename=%s%n",part.getName(), part.getSize(), part.getSubmittedFileName());
            }
        }
    }
    
    private static Server server;
    private static LocalConnector connector;
    private static File locationDir;
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        Path tmpDir = MavenTestingUtils.getTargetTestingPath("testrequest_getparts");
        FS.ensureEmpty(tmpDir);
        
        locationDir = tmpDir.toFile();
        
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);
        
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        server.setHandler(context);
        
        ServletHolder holder = context.addServlet(DumpPartInfoServlet.class,"/dump/*");
        String location = locationDir.getAbsolutePath();
        long maxFileSize = 1024*1024*5;
        long maxRequestSize = 1024*1024*10;
        int fileSizeThreshold = 1;
        MultipartConfigElement multipartConfig = new MultipartConfigElement(location,maxFileSize,maxRequestSize,fileSizeThreshold);
        ((ServletHolder.Registration) holder.getRegistration()).setMultipartConfig(multipartConfig);
        
        server.start();
    }
    
    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Test
    public void testMultiFileUpload_SameName() throws Exception
    {
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        // test GET
        request.setMethod("POST");
        request.setURI("/dump/");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host","tester");
        request.setHeader("Connection","close");

        String boundary="XyXyXy";
        request.setHeader("Content-Type","multipart/form-data; boundary=" + boundary);
        
        String crocMsg = "See ya later, aligator.";
        String aligMsg = "In a while, crocodile.";

        StringBuilder content = new StringBuilder();
        content.append("--").append(boundary).append("\r\n");
        content.append("Content-Disposition: form-data; name=\"same\"; filename=\"crocodile.dat\"\r\n");
        content.append("Content-Type: application/octet-stream\r\n");
        content.append("\r\n");
        content.append(crocMsg).append("\r\n");
        content.append("--").append(boundary).append("\r\n");
        content.append("Content-Disposition: form-data; name=\"same\"; filename=\"aligator.dat\"\r\n");
        content.append("Content-Type: application/octet-stream\r\n");
        content.append("\r\n");
        content.append(aligMsg).append("\r\n");
        content.append("--").append(boundary).append("--\r\n");
        content.append("\r\n");
        
        request.setContent(content.toString());

        response = HttpTester.parseResponse(connector.getResponses(request.generate()));
        assertThat("Response status", response.getStatus(), is(HttpServletResponse.SC_OK));
        assertEquals(HttpServletResponse.SC_OK,response.getStatus());
        
        String responseContents = response.getContent();
        assertThat("response.contents", responseContents, containsString(String.format("Got part: name=same, size=%d, filename=crocodile.dat",crocMsg.length())));
        assertThat("response.contents", responseContents, containsString(String.format("Got part: name=same, size=%d, filename=aligator.dat",aligMsg.length())));
    }
}
