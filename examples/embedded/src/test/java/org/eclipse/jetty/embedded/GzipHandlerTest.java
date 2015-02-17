//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.embedded;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.zip.GZIPInputStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.toolchain.test.AdvancedRunner;
import org.eclipse.jetty.util.IO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AdvancedRunner.class)
public class GzipHandlerTest
{
    private static String __content =
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit. In quis felis nunc. "+
        "Quisque suscipit mauris et ante auctor ornare rhoncus lacus aliquet. Pellentesque "+
        "habitant morbi tristique senectus et netus et malesuada fames ac turpis egestas. "+
        "Vestibulum sit amet felis augue, vel convallis dolor. Cras accumsan vehicula diam "+
        "at faucibus. Etiam in urna turpis, sed congue mi. Morbi et lorem eros. Donec vulputate "+
        "velit in risus suscipit lobortis. Aliquam id urna orci, nec sollicitudin ipsum. "+
        "Cras a orci turpis. Donec suscipit vulputate cursus. Mauris nunc tellus, fermentum "+
        "eu auctor ut, mollis at diam. Quisque porttitor ultrices metus, vitae tincidunt massa "+
        "sollicitudin a. Vivamus porttitor libero eget purus hendrerit cursus. Integer aliquam "+
        "consequat mauris quis luctus. Cras enim nibh, dignissim eu faucibus ac, mollis nec neque. "+
        "Aliquam purus mauris, consectetur nec convallis lacinia, porta sed ante. Suspendisse "+
        "et cursus magna. Donec orci enim, molestie a lobortis eu, imperdiet vitae neque.";

    private Server _server;
    private LocalConnector _connector;

    @Before
    public void init() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        Handler testHandler = new AbstractHandler()
        {
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException,
                    ServletException
            {
                PrintWriter writer = response.getWriter();
                writer.write(__content);
                writer.close();

                baseRequest.setHandled(true);
            }
        };

        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.setHandler(testHandler);

        _server.setHandler(gzipHandler);
        _server.start();
    }

    @After
    public void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testGzipHandler() throws Exception
    {
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setHeader("accept-encoding","gzip");
        request.setURI("/");

        response = HttpTester.parseResponse(_connector.getResponses(request.generate()));

        assertTrue(response.get("Content-Encoding").equalsIgnoreCase("gzip"));
        assertEquals(HttpServletResponse.SC_OK,response.getStatus());

        InputStream testIn = new GZIPInputStream(new ByteArrayInputStream(response.getContentBytes()));
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();
        IO.copy(testIn,testOut);

        assertEquals(__content, testOut.toString("UTF8"));

    }
}
