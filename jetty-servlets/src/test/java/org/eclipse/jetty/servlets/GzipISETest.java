//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.EnumSet;
import javax.servlet.DispatcherType;

import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletTester;
import org.eclipse.jetty.toolchain.test.http.SimpleHttpParser;
import org.eclipse.jetty.toolchain.test.http.SimpleHttpResponse;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

@RunWith(JUnit4.class)
public class GzipISETest
{
    private static final Logger LOG = Log.getLogger(GzipISETest.class);

    private ServletTester servletTester = new ServletTester("/ctx");
    private String host;
    private int port;
    private FilterHolder gzipFilterHolder;
    private SimpleHttpParser httpParser = new SimpleHttpParser();

    @Before
    public void setUp() throws Exception
    {
        HttpURI uri = new HttpURI(servletTester.createConnector(true));
        host = uri.getHost();
        port = uri.getPort();
        gzipFilterHolder = servletTester.addFilter(GzipFilter.class, "/*", EnumSet.allOf(DispatcherType.class));
        gzipFilterHolder.start();
        gzipFilterHolder.initialize();

        ServletHolder servletHolder = servletTester.addServlet(DefaultServlet.class, "/*");
        servletHolder.setInitParameter("resourceBase","src/test/resources/big_script.js");
        servletHolder.setInitParameter("maxCachedFiles","10");
        servletHolder.setInitParameter("dirAllowed","true");
        servletHolder.start();
        servletHolder.initialize();

        servletTester.start();
    }

    /**
     * This is a regression test for #409403. This test uses DefaultServlet + ResourceCache + GzipFilter to walk
     * through a code path that writes every single byte individually into HttpOutput's _aggregate buffer. The bug
     * never occured in plain http as the buffer gets passed around up to EndPoint.flush() where it gets cleared.
     * This test is supposed to assure that future changes won't break this.
     *
     * @throws IOException
     */
    @Test
    public void testISE() throws IOException
    {
        Socket socket = new Socket(host, port);
        socket.setSoTimeout(10000);
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        String request = "GET /ctx/ HTTP/1.0\r\n";
        request += "Host: localhost:" + port + "\r\n";
//        request += "accept-encoding: gzip\r\n";
        request += "\r\n";
        socket.getOutputStream().write(request.getBytes("UTF-8"));
        socket.getOutputStream().flush();
        SimpleHttpResponse response = httpParser.readResponse(reader);

        assertThat("response body length is as expected", response.getBody().length(), is(76846));
    }
}
