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

package org.eclipse.jetty.server;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class HttpOutputTest
{
    private Server _server;
    private LocalConnector _connector;
    private ContentHandler _handler;

    @Before
    public void init() throws Exception
    {
        _server = new Server();

        HttpConnectionFactory http = new HttpConnectionFactory();
        http.getHttpConfiguration().setRequestHeaderSize(1024);
        http.getHttpConfiguration().setResponseHeaderSize(1024);
        http.getHttpConfiguration().setOutputBufferSize(4096);
        
        _connector = new LocalConnector(_server,http,null);
        _server.addConnector(_connector);
        _handler=new ContentHandler();
        _server.setHandler(_handler);
        _server.start();
    }

    @After
    public void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testSimple() throws Exception
    {
        String response=_connector.getResponses("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response,containsString("HTTP/1.1 200 OK"));
    }
    
    @Test
    public void testSendInputStreamSimple() throws Exception
    {
        Resource simple = Resource.newClassPathResource("simple/simple.txt");
        _handler._contentInputStream=simple.getInputStream();
        String response=_connector.getResponses("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,containsString("Content-Length: 11"));
    }
    
    @Test
    public void testSendInputStreamBig() throws Exception
    {
        Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._contentInputStream=big.getInputStream();
        String response=_connector.getResponses("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,Matchers.not(containsString("Content-Length")));
    }
    
    @Test
    public void testSendInputStreamBigChunked() throws Exception
    {
        Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._contentInputStream= new FilterInputStream(big.getInputStream())
        {
            @Override
            public int read(byte[] b, int off, int len) throws IOException
            {
                int filled= super.read(b,off,len>2000?2000:len);
                return filled;
            }
        };
        String response=_connector.getResponses(
            "GET / HTTP/1.1\nHost: localhost:80\n\n"+
            "GET / HTTP/1.1\nHost: localhost:80\nConnection: close\n\n"
        );
        response=response.substring(0,response.lastIndexOf("HTTP/1.1 200 OK"));
        
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,containsString("Transfer-Encoding: chunked"));
        assertThat(response,containsString("\r\n0\r\n"));
    }
    

    @Test
    public void testSendChannelSimple() throws Exception
    {
        Resource simple = Resource.newClassPathResource("simple/simple.txt");
        _handler._contentChannel=simple.getReadableByteChannel();
        String response=_connector.getResponses("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,containsString("Content-Length: 11"));
    }
    
    @Test
    public void testSendChannelBig() throws Exception
    {
        Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._contentChannel=big.getReadableByteChannel();
        String response=_connector.getResponses("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,Matchers.not(containsString("Content-Length")));
    }
    
    @Test
    public void testSendChannelBigChunked() throws Exception
    {
        Resource big = Resource.newClassPathResource("simple/big.txt");
        final ReadableByteChannel channel = big.getReadableByteChannel();
        _handler._contentChannel=new ReadableByteChannel()
        {
            
            @Override
            public boolean isOpen()
            {
                return channel.isOpen();
            }
            
            @Override
            public void close() throws IOException
            {
                channel.close();
            }
            
            @Override
            public int read(ByteBuffer dst) throws IOException
            {
                int filled=0;
                if (dst.position()==0 && dst.limit()>2000)
                {
                    int limit=dst.limit();
                    dst.limit(2000);
                    filled=channel.read(dst);
                    dst.limit(limit);
                }
                else 
                    filled=channel.read(dst);
                return filled;
            }
        };
        
        String response=_connector.getResponses(
            "GET / HTTP/1.1\nHost: localhost:80\n\n"+
            "GET / HTTP/1.1\nHost: localhost:80\nConnection: close\n\n"
        );
        response=response.substring(0,response.lastIndexOf("HTTP/1.1 200 OK"));
        
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,containsString("Transfer-Encoding: chunked"));
        assertThat(response,containsString("\r\n0\r\n"));
    }
    
    static class ContentHandler extends AbstractHandler
    {
        InputStream _contentInputStream;
        ReadableByteChannel _contentChannel;
        
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setContentType("text/plain");
            
            HttpOutput out = (HttpOutput) response.getOutputStream();
            
            if (_contentInputStream!=null)
            {
                out.sendContent(_contentInputStream);
                _contentInputStream=null;
                return;
            }
            
            if (_contentChannel!=null)
            {
                out.sendContent(_contentChannel);
                _contentChannel=null;
                return;
            }
            
        }
        
    }
}


