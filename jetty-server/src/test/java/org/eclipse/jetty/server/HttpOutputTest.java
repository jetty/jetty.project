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

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.BufferUtil;
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
        assertThat(response,containsString("400\tThis is a big file"));
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
        assertThat(response,containsString("400\tThis is a big file"));
    }
    
    @Test
    public void testSendBigDirect() throws Exception
    {
        Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._content=BufferUtil.toBuffer(big,true);
        String response=_connector.getResponses("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,containsString("Content-Length"));
        assertThat(response,containsString("400\tThis is a big file"));
    }
    
    @Test
    public void testSendBigInDirect() throws Exception
    {
        Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._content=BufferUtil.toBuffer(big,false);
        String response=_connector.getResponses("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,containsString("Content-Length"));
        assertThat(response,containsString("400\tThis is a big file"));
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
    

    @Test
    public void testWriteSmall() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._content=BufferUtil.toBuffer(big,false);
        _handler._bytes=new byte[8];
        
        String response=_connector.getResponses("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,Matchers.not(containsString("Content-Length")));
        assertThat(response,containsString("400\tThis is a big file"));
    }
    
    @Test
    public void testWriteMed() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._content=BufferUtil.toBuffer(big,false);
        _handler._bytes=new byte[4000];
        
        String response=_connector.getResponses("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,Matchers.not(containsString("Content-Length")));
        assertThat(response,containsString("400\tThis is a big file"));
    }
    
    @Test
    public void testWriteLarge() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._content=BufferUtil.toBuffer(big,false);
        _handler._bytes=new byte[8192];
        
        String response=_connector.getResponses("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,Matchers.not(containsString("Content-Length")));
        assertThat(response,containsString("400\tThis is a big file"));
    }

    @Test
    public void testWriteBufferSmall() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._content=BufferUtil.toBuffer(big,false);
        _handler._buffer=BufferUtil.allocate(8);
        
        String response=_connector.getResponses("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,Matchers.not(containsString("Content-Length")));
        assertThat(response,containsString("400\tThis is a big file"));
    }
    
    @Test
    public void testWriteBufferMed() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._content=BufferUtil.toBuffer(big,false);
        _handler._buffer=BufferUtil.allocate(4000);
        
        String response=_connector.getResponses("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,Matchers.not(containsString("Content-Length")));
        assertThat(response,containsString("400\tThis is a big file"));
    }
    
    @Test
    public void testWriteBufferLarge() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._content=BufferUtil.toBuffer(big,false);
        _handler._buffer=BufferUtil.allocate(8192);
        
        String response=_connector.getResponses("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,Matchers.not(containsString("Content-Length")));
        assertThat(response,containsString("400\tThis is a big file"));
    }

    @Test
    public void testAsyncWriteSmall() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._content=BufferUtil.toBuffer(big,false);
        _handler._bytes=new byte[8];
        _handler._async=true;
        
        String response=_connector.getResponses("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,Matchers.not(containsString("Content-Length")));
        assertThat(response,containsString("400\tThis is a big file"));
    }
    
    @Test
    public void testAsyncWriteMed() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._content=BufferUtil.toBuffer(big,false);
        _handler._bytes=new byte[4000];
        _handler._async=true;
        
        String response=_connector.getResponses("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,Matchers.not(containsString("Content-Length")));
        assertThat(response,containsString("400\tThis is a big file"));
    }
    
    @Test
    public void testAsyncWriteLarge() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._content=BufferUtil.toBuffer(big,false);
        _handler._bytes=new byte[8192];
        _handler._async=true;
        
        String response=_connector.getResponses("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,Matchers.not(containsString("Content-Length")));
        assertThat(response,containsString("400\tThis is a big file"));
    }

    @Test
    public void testAsyncWriteBufferSmall() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._content=BufferUtil.toBuffer(big,false);
        _handler._buffer=BufferUtil.allocate(8);
        _handler._async=true;
        
        String response=_connector.getResponses("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,Matchers.not(containsString("Content-Length")));
        assertThat(response,containsString("400\tThis is a big file"));
    }
    
    @Test
    public void testAsyncWriteBufferMed() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._content=BufferUtil.toBuffer(big,false);
        _handler._buffer=BufferUtil.allocate(4000);
        _handler._async=true;
        
        String response=_connector.getResponses("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,Matchers.not(containsString("Content-Length")));
        assertThat(response,containsString("400\tThis is a big file"));
    }
    
    @Test
    public void testAsyncWriteBufferLarge() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._content=BufferUtil.toBuffer(big,false);
        _handler._buffer=BufferUtil.allocate(8192);
        _handler._async=true;
        
        String response=_connector.getResponses("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response,containsString("HTTP/1.1 200 OK"));
        assertThat(response,Matchers.not(containsString("Content-Length")));
        assertThat(response,containsString("400\tThis is a big file"));
    }
    
    static class ContentHandler extends AbstractHandler
    {
        boolean _async;
        ByteBuffer _buffer;
        byte[] _bytes;
        ByteBuffer _content;
        InputStream _contentInputStream;
        ReadableByteChannel _contentChannel;
        
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setContentType("text/plain");
            
            final HttpOutput out = (HttpOutput) response.getOutputStream();
            
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
            
            if (_bytes!=null)
            {
                if (_async)
                {
                    final AsyncContext async = request.startAsync();
                    out.setWriteListener(new WriteListener()
                    {
                        @Override
                        public void onWritePossible() throws IOException
                        {
                            while (out.isReady())
                            {
                                int len=_content.remaining();
                                if (len>_bytes.length)
                                    len=_bytes.length;
                                if (len==0)
                                {
                                    async.complete();
                                    break;
                                }
                                
                                _content.get(_bytes,0,len);
                                out.write(_bytes,0,len);
                            }
                        }

                        @Override
                        public void onError(Throwable t)
                        {
                            t.printStackTrace();
                            async.complete();
                        }
                    });
                    
                    return;  
                }
                
                
                while(BufferUtil.hasContent(_content))
                {
                    int len=_content.remaining();
                    if (len>_bytes.length)
                        len=_bytes.length;
                    _content.get(_bytes,0,len);
                    out.write(_bytes,0,len);
                }
                
                return;
            }
            
            if (_buffer!=null)
            {
                if (_async)
                {
                    final AsyncContext async = request.startAsync();
                    out.setWriteListener(new WriteListener()
                    {
                        @Override
                        public void onWritePossible() throws IOException
                        {
                            while (out.isReady())
                            {
                                if(BufferUtil.isEmpty(_content))
                                {
                                    async.complete();
                                    break;
                                }
                                    
                                BufferUtil.clearToFill(_buffer);
                                BufferUtil.put(_content,_buffer);
                                BufferUtil.flipToFlush(_buffer,0);
                                out.write(_buffer);
                            }
                        }

                        @Override
                        public void onError(Throwable t)
                        {
                            t.printStackTrace();
                            async.complete();
                        }
                    });
                    
                    return;  
                }
                
                
                while(BufferUtil.hasContent(_content))
                {
                    BufferUtil.clearToFill(_buffer);
                    BufferUtil.put(_content,_buffer);
                    BufferUtil.flipToFlush(_buffer,0);
                    out.write(_buffer);
                }
                
                return;
            }
            
            
            if (_content!=null)
            {
                out.sendContent(_content);
                _content=null;
                return;
            }
            
            
        }
        
    }
}


