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
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.xml.XmlParser;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.nio.ByteBuffer;

/**
 *
 */
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
    public void get_stats()
        throws Exception
    {
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        _server.setHandler( contexts );

        StatisticsHandler statsHandler = new StatisticsHandler();

        ServletContextHandler statsContext = new ServletContextHandler( _server, "/" );
        statsContext.setHandler( statsHandler );

        _server.setHandler( statsContext );

        statsContext.addServlet( new ServletHolder( new TestServlet() ), "/test1" );

        ServletHolder servletHolder = new ServletHolder( new StatisticsServlet() );
        servletHolder.setInitParameter( "restrictToLocalhost", "false" );

        statsContext.addServlet( servletHolder, "/stats" );

        statsContext.setSessionHandler( new SessionHandler() );

        _server.start();

        String response = getResponse("/test1" );

        response = getResponse("/stats?xml=true" );

        Stats stats = parseStats( response );

        Assert.assertEquals(1, stats.responses2xx);

        response = getResponse("/stats?statsReset=true" );

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
        XmlParser xmlParser = new XmlParser();
        Stats stats = new Stats();

        xmlParser.addContentHandler( "responses4xx", new DefaultHandler()
        {
            @Override
            public void characters( char[] ch, int start, int length )
                throws SAXException
            {
                try
                {
                    stats.responses4xx = Integer.parseInt( new String( ch, start, length  ) );
                }
                catch ( NumberFormatException e )
                {
                    //
                }
            }
        } );

        xmlParser.addContentHandler( "responses2xx", new DefaultHandler()
        {
            @Override
            public void characters( char[] ch, int start, int length )
                throws SAXException
            {
                try
                {
                    stats.responses2xx = Integer.parseInt( new String( ch, start, length  ) );
                }
                catch ( NumberFormatException e )
                {
                    //
                }
            }
        } );

        xmlParser.parse( new InputSource( new StringReader( xml ) ) );
        return stats;
    }

    public static class Stats
    {
        int responses2xx,responses4xx;
    }

    public static class ValueContentHandler
        extends DefaultHandler
    {
        @Override
        public void characters( char[] ch, int start, int length )
            throws SAXException
        {
            super.characters( ch, start, length );
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
