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

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.server.session.SessionHandler;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.xml.sax.InputSource;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.nio.ByteBuffer;

public class StatisticsServletTest
{
    private Server _server;

    private LocalConnector _connector;

    @Before
    public void createServer()
    {
        _server = new Server();
        _connector = new LocalConnector( _server );
        _server.addConnector( _connector );
    }


    @After
    public void destroyServer()
        throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void getStats()
        throws Exception
    {
        StatisticsHandler statsHandler = new StatisticsHandler();
        _server.setHandler(statsHandler);
        ServletContextHandler statsContext = new ServletContextHandler(statsHandler, "/");
        statsContext.addServlet( new ServletHolder( new TestServlet() ), "/test1" );
        ServletHolder servletHolder = new ServletHolder( new StatisticsServlet() );
        servletHolder.setInitParameter( "restrictToLocalhost", "false" );
        statsContext.addServlet( servletHolder, "/stats" );
        statsContext.setSessionHandler( new SessionHandler() );
        _server.start();

        getResponse("/test1" );
        String response = getResponse("/stats?xml=true" );
        Stats stats = parseStats( response );

        Assert.assertEquals(1, stats.responses2xx);

        getResponse("/stats?statsReset=true" );
        response = getResponse("/stats?xml=true" );
        stats = parseStats( response );

        Assert.assertEquals(1, stats.responses2xx);

        getResponse("/test1" );
        getResponse("/nothing" );
        response = getResponse("/stats?xml=true" );
        stats = parseStats( response );

        Assert.assertEquals(3, stats.responses2xx);
        Assert.assertEquals(1, stats.responses4xx);
    }

    public String getResponse( String path )
        throws Exception
    {
        HttpTester.Request request = new HttpTester.Request();
        request.setMethod( "GET" );
        request.setURI( path );
        request.setVersion( HttpVersion.HTTP_1_1 );
        request.setHeader( "Host", "test" );

        ByteBuffer responseBuffer = _connector.getResponse( request.generate() );
        return HttpTester.parseResponse( responseBuffer ).getContent();
    }


    public Stats parseStats( String xml )
        throws Exception
    {
        XPath xPath = XPathFactory.newInstance().newXPath();

        String responses4xx = xPath.evaluate( "//responses4xx", new InputSource( new StringReader( xml ) ) );

        String responses2xx = xPath.evaluate( "//responses2xx", new InputSource( new StringReader( xml ) ) );

        return new Stats(Integer.parseInt( responses2xx), Integer.parseInt( responses4xx ));
    }

    public static class Stats
    {
        int responses2xx,responses4xx;

        public Stats( int responses2xx, int responses4xx )
        {
            this.responses2xx = responses2xx;
            this.responses4xx = responses4xx;
        }
    }


    public static class TestServlet
        extends HttpServlet
    {

        @Override
        protected void doGet( HttpServletRequest req, HttpServletResponse resp )
            throws ServletException, IOException
        {
            resp.setStatus( HttpServletResponse.SC_OK );
            PrintWriter writer = resp.getWriter();
            writer.write( "Yup!!" );
        }
    }

}
