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

package org.eclipse.jetty.client;

import static org.junit.Assert.assertEquals;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.Test;

/* ------------------------------------------------------------ */
public class ErrorStatusTest
    extends ContentExchangeTest
{

    /* ------------------------------------------------------------ */
    @Test
    public void testPutBadRequest()
        throws Exception
    {
        doPutFail(HttpStatus.BAD_REQUEST_400);
    }
    
    /* ------------------------------------------------------------ */
    @Test
    public void testPutUnauthorized()
        throws Exception
    {
        doPutFail(HttpStatus.UNAUTHORIZED_401);
    }
    
    /* ------------------------------------------------------------ */
    @Test
    public void testPutForbidden()
        throws Exception
    {
        doPutFail(HttpStatus.FORBIDDEN_403);
    }
    
    /* ------------------------------------------------------------ */
    @Test
    public void testPutNotFound()
        throws Exception
    {
        doPutFail(HttpStatus.NOT_FOUND_404);    
    }
    
    /* ------------------------------------------------------------ */
    @Test
    public void testPutServerError()
        throws Exception
    {
        doPutFail(HttpStatus.INTERNAL_SERVER_ERROR_500);    
    }
    
    /* ------------------------------------------------------------ */
    @Test
    public void testGetBadRequest()
        throws Exception
    {
        doGetFail(HttpStatus.BAD_REQUEST_400);
    }
    
    /* ------------------------------------------------------------ */
    @Test
    public void testGetUnauthorized()
        throws Exception
    {
        doGetFail(HttpStatus.UNAUTHORIZED_401);
    }
    
    /* ------------------------------------------------------------ */
    @Test
    public void testGetNotFound()
        throws Exception
    {
        doGetFail(HttpStatus.NOT_FOUND_404);    
    }
    
    /* ------------------------------------------------------------ */
    @Test
    public void testGetServerError()
        throws Exception
    {
        doGetFail(HttpStatus.INTERNAL_SERVER_ERROR_500);    
    }
    
    /* ------------------------------------------------------------ */
    @Test
    public void testPostBadRequest() 
        throws Exception
    {
        doPostFail(HttpStatus.BAD_REQUEST_400);
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testPostUnauthorized()
        throws Exception
    {
        doPostFail(HttpStatus.UNAUTHORIZED_401);
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testPostForbidden()
        throws Exception
    {
        doPostFail(HttpStatus.FORBIDDEN_403);
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testPostNotFound()
        throws Exception
    {
        doPostFail(HttpStatus.NOT_FOUND_404);
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testPostServerError()
        throws Exception
    {
        doPostFail(HttpStatus.INTERNAL_SERVER_ERROR_500);
    }

    /* ------------------------------------------------------------ */
    protected void doPutFail(int status)
        throws Exception
    {
        startClient(getRealm());

        ContentExchange putExchange = new ContentExchange();
        putExchange.setURL(getBaseUrl() + "output.txt");
        putExchange.setMethod(HttpMethods.PUT);
        putExchange.setRequestHeader("X-Response-Status",Integer.toString(status));
        putExchange.setRequestContent(new ByteArrayBuffer(getContent().getBytes()));
        
        getClient().send(putExchange);
        int state = putExchange.waitForDone();
                
        int responseStatus = putExchange.getResponseStatus();
        
        stopClient();

        assertEquals(status, responseStatus);            
    }
    
    /* ------------------------------------------------------------ */
    protected void doGetFail(int status)
        throws Exception
    {
        startClient(getRealm());

        ContentExchange getExchange = new ContentExchange();
        getExchange.setURL(getBaseUrl() + "input.txt");
        getExchange.setMethod(HttpMethods.GET);
        getExchange.setRequestHeader("X-Response-Status",Integer.toString(status));
       
        getClient().send(getExchange);
        int state = getExchange.waitForDone();
        
        String content;
        int responseStatus = getExchange.getResponseStatus();
        if (responseStatus == HttpStatus.OK_200) {
            content = getExchange.getResponseContent();
        }
        
        stopClient();

        assertEquals(status, responseStatus);
    }
    
    /* ------------------------------------------------------------ */
    protected void doPostFail(int status)
        throws Exception
    {
        startClient(getRealm());
    
        ContentExchange postExchange = new ContentExchange();
        postExchange.setURL(getBaseUrl() + "test");
        postExchange.setMethod(HttpMethods.POST);
        postExchange.setRequestHeader("X-Response-Status",Integer.toString(status));
        postExchange.setRequestContent(new ByteArrayBuffer(getContent().getBytes()));
        
        getClient().send(postExchange);
        int state = postExchange.waitForDone();
                
        int responseStatus = postExchange.getResponseStatus();
        
        stopClient();
    
        assertEquals(status, responseStatus);            
    }

    /* ------------------------------------------------------------ */
    protected void configureServer(Server server)
        throws Exception
    {
        setProtocol("http");
    
        SelectChannelConnector connector = new SelectChannelConnector();
        server.addConnector(connector);
        
        ServletContextHandler root = new ServletContextHandler();
        root.setContextPath("/");
        root.setResourceBase(getBasePath());
        ServletHolder servletHolder = new ServletHolder( new DefaultServlet() );
        servletHolder.setInitParameter( "gzip", "true" );
        root.addServlet( servletHolder, "/*" );    

        Handler status = new StatusHandler();
        Handler test = new TestHandler(getBasePath());
        
        HandlerCollection handlers = new HandlerCollection();
        handlers.setHandlers(new Handler[]{status, test, root});
        server.setHandler( handlers ); 
    }
    
    /* ------------------------------------------------------------ */
    protected static class StatusHandler extends AbstractHandler {
        /* ------------------------------------------------------------ */
        public void handle(String target, Request baseRequest,
                HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException
        {
            if (baseRequest.isHandled())
                return;

            int statusValue = 0;
            String statusHeader = request.getHeader("X-Response-Status");
            if (statusHeader != null)
            {
                statusValue = Integer.parseInt(statusHeader);
            }
            if (statusValue != 0)
            {
                response.setStatus(statusValue);
                baseRequest.setHandled(true);
            }
        }
    }
}
