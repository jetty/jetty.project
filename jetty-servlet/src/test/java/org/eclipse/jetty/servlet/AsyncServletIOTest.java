//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.assertEquals;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

// TODO need  these on SPDY as well!
public class AsyncServletIOTest 
{    
    protected AsyncIOServlet _servlet=new AsyncIOServlet();
    protected int _port;

    protected Server _server = new Server();
    protected ServletHandler _servletHandler;
    protected ServerConnector _connector;

    @Before
    public void setUp() throws Exception
    {
        HttpConfiguration http_config = new HttpConfiguration();
        http_config.setOutputBufferSize(4096);
        _connector = new ServerConnector(_server,new HttpConnectionFactory(http_config));
        
        _server.setConnectors(new Connector[]{ _connector });
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SECURITY|ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/ctx");
        _server.setHandler(context);
        _servletHandler=context.getServletHandler();
        ServletHolder holder=new ServletHolder(_servlet);
        holder.setAsyncSupported(true);
        _servletHandler.addServletWithMapping(holder,"/path/*");
        _server.start();
        _port=_connector.getLocalPort();

        _owp.set(0);
        _oda.set(0);
        _read.set(0);
    }

    @After
    public void tearDown() throws Exception
    {
        _server.stop();
    }
    
    @Test
    public void testEmpty() throws Exception
    {
        process();
    }
    
    @Test
    public void testWrite() throws Exception
    {
        process(10);
    }
    
    @Test
    public void testWrites() throws Exception
    {
        process(10,1,20,10);
    }
    
    @Test
    public void testWritesFlushWrites() throws Exception
    {
        process(10,1,0,20,10);
    }

    @Test
    public void testBigWrite() throws Exception
    {
        process(102400);
    }
    
    @Test
    public void testBigWrites() throws Exception
    {
        process(102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400);
        Assert.assertThat(_owp.get(),Matchers.greaterThan(1));
    }

    @Test
    public void testRead() throws Exception
    {
        process("Hello!!!\r\n");
    }

    @Test
    public void testBigRead() throws Exception
    {
        process("Now is the time for all good men to come to the aid of the party. How now Brown Cow. The quick brown fox jumped over the lazy dog. The moon is blue to a fish in love.\r\n");
    }

    @Test
    public void testReadWrite() throws Exception
    {
        process("Hello!!!\r\n",10);
    }
    
    
    protected void assertContains(String content,String response)
    {
        Assert.assertThat(response,Matchers.containsString(content));
    }
    
    protected void assertNotContains(String content,String response)
    {
        Assert.assertThat(response,Matchers.not(Matchers.containsString(content)));
    }

    public synchronized List<String> process(String content,int... writes) throws Exception
    {
        return process(content.getBytes("ISO-8859-1"),writes);
    }

    public synchronized List<String> process(int... writes) throws Exception
    {
        return process((byte[])null,writes);
    }
    
    public synchronized List<String> process(byte[] content, int... writes) throws Exception
    {
        StringBuilder request = new StringBuilder(512);
        request.append("GET /ctx/path/info");
        char s='?';
        for (int w: writes)
        {
            request.append(s).append("w=").append(w);
            s='&';
        }
        
        request.append(" HTTP/1.1\r\n")
        .append("Host: localhost\r\n")
        .append("Connection: close\r\n");
        
        if (content!=null)
            request.append("Content-Length: "+content.length+"\r\n")
            .append("Content-Type: text/plain\r\n");
        
        request.append("\r\n");
        
        int port=_port;
        List<String> list = new ArrayList<>();
        try (Socket socket = new Socket("localhost",port);)
        {
            socket.setSoTimeout(1000000);
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes("ISO-8859-1"));

            if (content!=null && content.length>0)
            {
                Thread.sleep(100);
                out.write(content[0]);
                Thread.sleep(100);
                int half=(content.length-1)/2;
                out.write(content,1,half);
                Thread.sleep(100);
                out.write(content,1+half,content.length-half-1);
            }
            
            
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()),102400);
            
            // response line
            String line = in.readLine();
            //System.err.println("line: "+line);
            Assert.assertThat(line,Matchers.startsWith("HTTP/1.1 200 OK"));
            
            // Skip headers
            while (line!=null)
            {
                line = in.readLine();
                //System.err.println("line: "+line);
                if (line.length()==0)
                    break;
            }

            // Get body slowly
            while (true)
            {
                line = in.readLine();
                if (line==null)
                    break;
                //System.err.println("line:  "+line.length()+"\t"+(line.length()>40?(line.substring(0,40)+"..."):line));
                list.add(line);
                Thread.sleep(50);
            }
        }
        
        // check lines
        int w=0;
        for (String line : list)
        {
            // System.err.println(line);
            if ("-".equals(line))
                continue;
            assertEquals(writes[w],line.length());
            assertEquals(line.charAt(0),'0'+(w%10));
            
            w++;
            if (w<writes.length && writes[w]<=0)
                w++;
        }
        
        if (content!=null)
            Assert.assertEquals(content.length,_read.get());
        
        return list;
    }

    static AtomicInteger _owp = new AtomicInteger();
    static AtomicInteger _oda = new AtomicInteger();
    static AtomicInteger _read = new AtomicInteger();
    
    private static class AsyncIOServlet extends HttpServlet
    {
        private static final long serialVersionUID = -8161977157098646562L;
        
        public AsyncIOServlet()
        {}
        
        /* ------------------------------------------------------------ */
        @Override
        public void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
        {
            final AsyncContext async = request.startAsync();
            final AtomicInteger complete = new AtomicInteger(2);
            final AtomicBoolean onDataAvailable = new AtomicBoolean(false);
            
            // Asynchronous Read
            if (request.getContentLength()>0)
            {
                // System.err.println("reading "+request.getContentLength());
                final ServletInputStream in=request.getInputStream();
                in.setReadListener(new ReadListener()
                {
                    byte[] _buf=new byte[32];
                    @Override
                    public void onError(Throwable t)
                    {      
                        if (complete.decrementAndGet()==0)
                            async.complete();                  
                    }
                    
                    @Override
                    public void onDataAvailable() throws IOException
                    {                
                        if (!onDataAvailable.compareAndSet(false,true))
                            throw new IllegalStateException();
                        
                        // System.err.println("ODA");
                        while (in.isReady())
                        {
                            _oda.incrementAndGet();
                            int len=in.read(_buf);
                            // System.err.println("read "+len);
                            if (len>0)
                                _read.addAndGet(len);
                        }

                        if (!onDataAvailable.compareAndSet(true,false))
                            throw new IllegalStateException();
                        
                    }
                    
                    @Override
                    public void onAllDataRead() throws IOException
                    {    
                        if (onDataAvailable.get())
                        {
                            System.err.println("OADR too early!");
                            _read.set(-1);
                        }
                        
                        // System.err.println("OADR");
                        if (complete.decrementAndGet()==0)
                            async.complete();
                    }
                });
            }
            else
                complete.decrementAndGet();
            
            
            // Asynchronous Write
            final String[] writes = request.getParameterValues("w");
            final ServletOutputStream out = response.getOutputStream();
            out.setWriteListener(new WriteListener()
            {
                int _w=0;
                
                @Override
                public void onWritePossible() throws IOException
                {
                    //System.err.println("OWP");
                    _owp.incrementAndGet();

                    while (writes!=null && _w< writes.length)
                    {
                        int write=Integer.valueOf(writes[_w++]);
                        
                        if (write==0)
                            out.flush();
                        else
                        {
                            byte[] buf=new byte[write+1];
                            Arrays.fill(buf,(byte)('0'+((_w-1)%10)));
                            buf[write]='\n';
                            out.write(buf);
                        }
                        
                        if (!out.isReady())
                            return;
                    }
                    
                    if (complete.decrementAndGet()==0)
                        async.complete();
                }

                @Override
                public void onError(Throwable t)
                {
                    async.complete();
                } 
            });
        }
    }
}
