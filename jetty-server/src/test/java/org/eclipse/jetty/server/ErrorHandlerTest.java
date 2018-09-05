//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;

import java.io.IOException;

import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class ErrorHandlerTest
{
    static Server server;
    static LocalConnector connector;
    
    @BeforeClass
    public static void before() throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);
        server.addBean(new ErrorHandler()
        {
            @Override
            protected void generateAcceptableResponse(
                Request baseRequest, 
                HttpServletRequest request, 
                HttpServletResponse response, 
                int code, 
                String message,
                String mimeType) throws IOException
            {
                switch(mimeType)
                {
                    case "text/json":
                    case "application/json":
                    {
                        baseRequest.setHandled(true);
                        response.setContentType(mimeType);
                        response.getWriter()
                         .append("{")
                         .append("code: \"").append(Integer.toString(code)).append("\",")
                         .append("message: \"").append(message).append('"')
                         .append("}");
                        break;
                    }
                    case "text/plain":
                    {
                        baseRequest.setHandled(true);
                        response.setContentType("text/plain");
                        response.getOutputStream().print(response.getContentType());
                        break;
                    }
                    default:
                        super.generateAcceptableResponse(baseRequest,request,response,code,message,mimeType);
                }
            }    
        });
        
        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                if (baseRequest.getDispatcherType()==DispatcherType.ERROR)
                {
                    baseRequest.setHandled(true);
                    response.sendError(((Integer)request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE)).intValue());
                    return;
                }
                
                if(target.startsWith("/charencoding/"))
                {
                    baseRequest.setHandled(true);
                    response.setCharacterEncoding("utf-8");
                    response.sendError(404);
                    return;
                }
                
                if(target.startsWith("/badmessage/"))
                {
                    throw new ServletException(new BadMessageException(Integer.valueOf(target.substring(12))));
                }
            }
        });
        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                
                if (baseRequest.getDispatcherType()==DispatcherType.ERROR)
                {
                    baseRequest.setHandled(true);
                    response.sendError(((Integer)request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE)).intValue());
                    return;
                }
                
                if(target.startsWith("/charencoding/"))
                {
                    baseRequest.setHandled(true);
                    response.setCharacterEncoding("utf-8");
                    response.sendError(404);
                    return;
                }
                
                if(target.startsWith("/badmessage/"))
                {
                    throw new ServletException(new BadMessageException(Integer.valueOf(target.substring(12))));
                }
            }
        });
        server.start();
    }
    
    @AfterClass
    public static void after() throws Exception
    {
        server.stop();
    }
    
    @Test
    public void test404NoAccept() throws Exception
    {
        String response = connector.getResponse(
            "GET / HTTP/1.1\r\n"+
            "Host: Localhost\r\n"+
            "\r\n");

        assertThat(response,startsWith("HTTP/1.1 404 "));
        assertThat(response,not(containsString("Content-Length: 0")));
        assertThat(response,containsString("Content-Type: text/html;charset=iso-8859-1"));
    }
    
    @Test
    public void test404EmptyAccept() throws Exception
    {
        String response = connector.getResponse(
            "GET / HTTP/1.1\r\n"+
            "Accept: \r\n"+
            "Host: Localhost\r\n"+
            "\r\n");
        assertThat(response,startsWith("HTTP/1.1 404 "));
        assertThat(response,containsString("Content-Length: 0"));
        assertThat(response,not(containsString("Content-Type")));
    }
    
    @Test
    public void test404UnAccept() throws Exception
    {
        String response = connector.getResponse(
            "GET / HTTP/1.1\r\n"+
            "Accept: text/*;q=0\r\n"+
            "Host: Localhost\r\n"+
            "\r\n");

        assertThat(response,startsWith("HTTP/1.1 404 "));
        assertThat(response,containsString("Content-Length: 0"));
        assertThat(response,not(containsString("Content-Type")));
    }
    
    @Test
    public void test404AllAccept() throws Exception
    {
        String response = connector.getResponse(
            "GET / HTTP/1.1\r\n"+
            "Host: Localhost\r\n"+
            "Accept: */*\r\n"+
            "\r\n");
        
        assertThat(response,startsWith("HTTP/1.1 404 "));
        assertThat(response,not(containsString("Content-Length: 0")));
        assertThat(response,containsString("Content-Type: text/html;charset=iso-8859-1"));
    }
    
    @Test
    public void test404HtmlAccept() throws Exception
    {
        String response = connector.getResponse(
            "GET / HTTP/1.1\r\n"+
            "Host: Localhost\r\n"+
            "Accept: text/html\r\n"+
            "\r\n");
        
        assertThat(response,startsWith("HTTP/1.1 404 "));
        assertThat(response,not(containsString("Content-Length: 0")));
        assertThat(response,containsString("Content-Type: text/html;charset=iso-8859-1"));
    }
    
    @Test
    public void test404HtmlAcceptAnyCharset() throws Exception
    {
        String response = connector.getResponse(
            "GET / HTTP/1.1\r\n"+
            "Host: Localhost\r\n"+
            "Accept: text/html\r\n"+
            "Accept-Charset: *\r\n"+
            "\r\n");
        
        assertThat(response,startsWith("HTTP/1.1 404 "));
        assertThat(response,not(containsString("Content-Length: 0")));
        assertThat(response,containsString("Content-Type: text/html;charset=utf-8"));
    }
    
    @Test
    public void test404HtmlAcceptUtf8Charset() throws Exception
    {
        String response = connector.getResponse(
            "GET / HTTP/1.1\r\n"+
            "Host: Localhost\r\n"+
            "Accept: text/html\r\n"+
            "Accept-Charset: utf-8\r\n"+
            "\r\n");
        
        assertThat(response,startsWith("HTTP/1.1 404 "));
        assertThat(response,not(containsString("Content-Length: 0")));
        assertThat(response,containsString("Content-Type: text/html;charset=utf-8"));
    }
    
    @Test
    public void test404HtmlAcceptNotUtf8Charset() throws Exception
    {
        String response = connector.getResponse(
            "GET / HTTP/1.1\r\n"+
            "Host: Localhost\r\n"+
            "Accept: text/html\r\n"+
            "Accept-Charset: utf-8;q=0\r\n"+
            "\r\n");
        
        assertThat(response,startsWith("HTTP/1.1 404 "));
        assertThat(response,not(containsString("Content-Length: 0")));
        assertThat(response,containsString("Content-Type: text/html;charset=iso-8859-1"));
    }
    
    @Test
    public void test404HtmlAcceptNotUtf8UnknownCharset() throws Exception
    {
        String response = connector.getResponse(
            "GET / HTTP/1.1\r\n"+
            "Host: Localhost\r\n"+
            "Accept: text/html\r\n"+
            "Accept-Charset: utf-8;q=0,unknown\r\n"+
            "\r\n");

        assertThat(response,startsWith("HTTP/1.1 404 "));
        assertThat(response,containsString("Content-Length: 0"));
        assertThat(response,not(containsString("Content-Type")));
    }
    
    @Test
    public void test404HtmlAcceptUnknownUtf8Charset() throws Exception
    {
        String response = connector.getResponse(
            "GET / HTTP/1.1\r\n"+
            "Host: Localhost\r\n"+
            "Accept: text/html\r\n"+
            "Accept-Charset: utf-8;q=0.1,unknown\r\n"+
            "\r\n");

        assertThat(response,startsWith("HTTP/1.1 404 "));
        assertThat(response,not(containsString("Content-Length: 0")));
        assertThat(response,containsString("Content-Type: text/html;charset=utf-8"));
    }
    
    @Test
    public void test404PreferHtml() throws Exception
    {
        String response = connector.getResponse(
            "GET / HTTP/1.1\r\n"+
            "Host: Localhost\r\n"+
            "Accept: text/html;q=1.0,text/json;q=0.5,*/*\r\n"+
            "Accept-Charset: *\r\n"+
            "\r\n");
        
        assertThat(response,startsWith("HTTP/1.1 404 "));
        assertThat(response,not(containsString("Content-Length: 0")));
        assertThat(response,containsString("Content-Type: text/html;charset=utf-8"));
    }
    
    @Test
    public void test404PreferJson() throws Exception
    {
        String response = connector.getResponse(
            "GET / HTTP/1.1\r\n"+
            "Host: Localhost\r\n"+
            "Accept: text/html;q=0.5,text/json;q=1.0,*/*\r\n"+
            "Accept-Charset: *\r\n"+
            "\r\n");
        
        assertThat(response,startsWith("HTTP/1.1 404 "));
        assertThat(response,not(containsString("Content-Length: 0")));
        assertThat(response,containsString("Content-Type: text/json"));
    }

    @Test
    public void testCharEncoding() throws Exception
    {
        String rawResponse = connector.getResponse(
                "GET /charencoding/foo HTTP/1.1\r\n"+
                        "Host: Localhost\r\n"+
                        "Accept: text/plain\r\n"+
                        "\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status code", response.getStatus(), is(404));
        HttpField contentType = response.getField(HttpHeader.CONTENT_TYPE);
        assertThat("Response Content-Type", contentType, is(notNullValue()));
        assertThat("Response Content-Type value", contentType.getValue(), not(containsString("null")));
    }

    @Test
    public void testBadMessage() throws Exception
    {
        String rawResponse = connector.getResponse(
                "GET /badmessage/444 HTTP/1.1\r\n"+
                        "Host: Localhost\r\n"+
                        "\r\n");
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status code", response.getStatus(), is(444));
    }
}
