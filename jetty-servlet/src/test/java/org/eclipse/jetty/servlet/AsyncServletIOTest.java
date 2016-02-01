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

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletResponse;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.DebugListener;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

// TODO need  these on HTTP2 as well!
public class AsyncServletIOTest 
{    
    private static final Logger LOG = Log.getLogger(AsyncServletIOTest.class);
    protected AsyncIOServlet _servlet0=new AsyncIOServlet();
    protected AsyncIOServlet2 _servlet2=new AsyncIOServlet2();
    protected AsyncIOServlet3 _servlet3=new AsyncIOServlet3();
    protected AsyncIOServlet4 _servlet4=new AsyncIOServlet4();
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
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/ctx");
        context.addEventListener(new DebugListener());
        _server.setHandler(context);
        _servletHandler=context.getServletHandler();
        
        
        ServletHolder holder=new ServletHolder(_servlet0);
        holder.setAsyncSupported(true);
        _servletHandler.addServletWithMapping(holder,"/path/*");
        
        ServletHolder holder2=new ServletHolder(_servlet2);
        holder2.setAsyncSupported(true);
        _servletHandler.addServletWithMapping(holder2,"/path2/*");
        
        ServletHolder holder3=new ServletHolder(_servlet3);
        holder3.setAsyncSupported(true);
        _servletHandler.addServletWithMapping(holder3,"/path3/*");
        
        ServletHolder holder4=new ServletHolder(_servlet4);
        holder4.setAsyncSupported(true);
        _servletHandler.addServletWithMapping(holder4,"/path4/*");
        
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
        process(102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400,102400);
        Assert.assertThat("On Write Possible",_owp.get(),greaterThanOrEqualTo(1));
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

    @Test
    public void testAsync2() throws Exception
    {
        StringBuilder request = new StringBuilder(512);
        request.append("GET /ctx/path2/info HTTP/1.1\r\n")
        .append("Host: localhost\r\n")
        .append("Connection: close\r\n")
        .append("\r\n");
        
        int port=_port;
        List<String> list = new ArrayList<>();
        try (Socket socket = new Socket("localhost",port))
        {
            socket.setSoTimeout(1000000);
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()),102400);
            
            // response line
            String line = in.readLine();
            LOG.debug("response-line: "+line);
            Assert.assertThat(line,startsWith("HTTP/1.1 200 OK"));
            
            // Skip headers
            while (line!=null)
            {
                line = in.readLine();
                LOG.debug("header-line: "+line);
                if (line.length()==0)
                    break;
            }

            // Get body slowly
            while (true)
            {
                line = in.readLine();
                LOG.debug("body: "+line);
                if (line==null)
                    break;
                list.add(line);
            }
        }

        Assert.assertEquals(list.get(0),"data");
        Assert.assertTrue(_servlet2.completed.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testAsyncConsumeAll() throws Exception
    {
        StringBuilder request = new StringBuilder(512);
        request.append("GET /ctx/path3/info HTTP/1.1\r\n")
        .append("Host: localhost\r\n")
        .append("Content-Type: text/plain\r\n")
        .append("Content-Length: 10\r\n")
        .append("\r\n")
        .append("0");
        
        int port=_port;
        try (Socket socket = new Socket("localhost",port))
        {
            socket.setSoTimeout(10000);
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()),102400);
            
            // response line
            String line = in.readLine();
            LOG.debug("response-line: "+line);
            Assert.assertThat(line,startsWith("HTTP/1.1 200 OK"));
            
            // Skip headers
            while (line!=null)
            {
                line = in.readLine();
                LOG.debug("header-line: "+line);
                if (line.length()==0)
                    break;
            }

            // Get body
            line = in.readLine();
            LOG.debug("body: "+line);
            Assert.assertEquals("DONE",line);

            // The connection should be aborted
            line = in.readLine();
            Assert.assertNull(line);
        }
    }
    
    
    
    public synchronized List<String> process(String content,int... writes) throws Exception
    {
        return process(content.getBytes(StandardCharsets.ISO_8859_1),writes);
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
            request.append("Content-Length: ").append(content.length).append("\r\n")
            .append("Content-Type: text/plain\r\n");
        
        request.append("\r\n");
        
        int port=_port;
        List<String> list = new ArrayList<>();
        try (Socket socket = new Socket("localhost",port))
        {
            socket.setSoTimeout(1000000);
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));

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
            LOG.debug("response-line: "+line);
            Assert.assertThat(line,startsWith("HTTP/1.1 200 OK"));
            
            // Skip headers
            while (line!=null)
            {
                line = in.readLine();
                LOG.debug("header-line:  "+line);
                if (line.length()==0)
                    break;
            }

            // Get body slowly
            while (true)
            {
                line = in.readLine();
                if (line==null)
                    break;
                LOG.debug("body:  "+brief(line));
                list.add(line);
                Thread.sleep(50);
            }
        }
        
        // check lines
        int w=0;
        for (String line : list)
        {
            LOG.debug("line:  "+brief(line));
            if ("-".equals(line))
                continue;
            assertEquals("Line Length",writes[w],line.length());
            assertEquals("Line Contents",line.charAt(0),'0'+(w%10));
            
            w++;
            if (w<writes.length && writes[w]<=0)
                w++;
        }
        
        if (content!=null)
            Assert.assertEquals("Content Length",content.length,_read.get());
        
        return list;
    }
    
    private static String brief(String line)
    {
        return line.length()+"\t"+(line.length()>40?(line.substring(0,40)+"..."):line);
    }

    static AtomicInteger _owp = new AtomicInteger();
    static AtomicInteger _oda = new AtomicInteger();
    static AtomicInteger _read = new AtomicInteger();
    
    private static class AsyncIOServlet extends HttpServlet
    {
        private static final long serialVersionUID = -8161977157098646562L;
        
        public AsyncIOServlet()
        {
        }
        
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
                        
                        //System.err.println("ODA");
                        while (in.isReady() && !in.isFinished())
                        {
                            _oda.incrementAndGet();
                            int len=in.read(_buf);
                            //System.err.println("read "+len);
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
                            LOG.warn("OADR too early!");
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
                    LOG.debug("OWP");
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

    @SuppressWarnings("serial")
    public class AsyncIOServlet2 extends HttpServlet
    {
        public CountDownLatch completed = new CountDownLatch(1);
        
        @Override
        public void doGet(final HttpServletRequest request, final HttpServletResponse response) throws IOException
        {
            new SampleAsycListener(request,response);
        }

        class SampleAsycListener implements WriteListener, AsyncListener
        {
            final ServletResponse response;
            final ServletOutputStream servletOutputStream;
            final AsyncContext asyncContext;
            volatile boolean written=false;

            SampleAsycListener(HttpServletRequest request,HttpServletResponse response) throws IOException
            {
                asyncContext = request.startAsync();
                asyncContext.setTimeout(10000L);
                asyncContext.addListener(this);
                servletOutputStream = response.getOutputStream();
                servletOutputStream.setWriteListener(this);
                this.response=response;
            }

            @Override
            public void onWritePossible() throws IOException
            {
                if (!written)
                {
                    written=true;
                    response.setContentLength(5);
                    servletOutputStream.write("data\n".getBytes());
                }
               
                if (servletOutputStream.isReady())
                {
                    asyncContext.complete();
                }
            }

            @Override
            public void onError(final Throwable t)
            {
                t.printStackTrace();
                asyncContext.complete();
            }

            @Override
            public void onComplete(final AsyncEvent event) throws IOException
            {
                completed.countDown();
            }

            @Override
            public void onTimeout(final AsyncEvent event) throws IOException
            {
                asyncContext.complete();
            }

            @Override
            public void onError(final AsyncEvent event) throws IOException
            {
                asyncContext.complete();
            }

            @Override
            public void onStartAsync(AsyncEvent event) throws IOException
            {

            }
        }
    }
    

    @SuppressWarnings("serial")
    public class AsyncIOServlet3 extends HttpServlet
    {
        public CountDownLatch completed = new CountDownLatch(1);
        
        @Override
        public void doGet(final HttpServletRequest request, final HttpServletResponse response) throws IOException
        {
            AsyncContext async = request.startAsync();
            
            request.getInputStream().setReadListener(new ReadListener()
            {
                
                @Override
                public void onError(Throwable t)
                {                    
                }
                
                @Override
                public void onDataAvailable() throws IOException
                {                    
                }
                
                @Override
                public void onAllDataRead() throws IOException
                {                    
                }
            });
            
            response.setStatus(200);
            response.getOutputStream().print("DONE");
            async.complete();
        }
    }
    

    @Test
    public void testCompleteWhilePending() throws Exception
    {
        _servlet4.onDA.set(0);
        _servlet4.onWP.set(0);
        
        StringBuilder request = new StringBuilder(512);
        request.append("POST /ctx/path4/info HTTP/1.1\r\n")
        .append("Host: localhost\r\n")
        .append("Content-Type: text/plain\r\n")
        .append("Content-Length: 20\r\n")
        .append("\r\n")
        .append("12345678\r\n");
        
        int port=_port;
        List<String> list = new ArrayList<>();
        try (Socket socket = new Socket("localhost",port))
        {
            socket.setSoTimeout(10000);
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(ISO_8859_1));
            out.flush();
            Thread.sleep(100);
            out.write("ABC".getBytes(ISO_8859_1));
            out.flush();
            Thread.sleep(100);
            out.write("DEF".getBytes(ISO_8859_1));
            out.flush();
            
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            
            // response line
            String line = in.readLine();
            LOG.debug("response-line: "+line);
            Assert.assertThat(line,startsWith("HTTP/1.1 200 OK"));
            
            boolean chunked=false;
            // Skip headers
            while (line!=null)
            {
                line = in.readLine();
                LOG.debug("header-line: "+line);
                chunked|="Transfer-Encoding: chunked".equals(line);
                if (line.length()==0)
                    break;
            }
            
            assertTrue(chunked);

            // Get body slowly
            String last=null;
            try
            {
                while (true)
                {
                    last=line;
                    //Thread.sleep(1000);
                    line = in.readLine();
                    LOG.debug("body: "+line);
                    if (line==null)
                        break;
                    list.add(line);
                }
            }
            catch(IOException e)
            {
                // ignored
            }

            LOG.debug("last: "+last);
            // last non empty line should contain some X's
            assertThat(last,containsString("X"));
            // last non empty line should not contain end chunk
            assertThat(last,not(containsString("0")));
        }

        assertTrue(_servlet4.completed.await(5, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertEquals(0,_servlet4.onDA.get());
        assertEquals(0,_servlet4.onWP.get());
        
        
    }
    
    @SuppressWarnings("serial")
    public class AsyncIOServlet4 extends HttpServlet
    {
        public CountDownLatch completed = new CountDownLatch(1);
        public AtomicInteger onDA = new AtomicInteger();
        public AtomicInteger onWP = new AtomicInteger();
        
        @Override
        public void doPost(final HttpServletRequest request, final HttpServletResponse response) throws IOException
        {
            final AsyncContext async = request.startAsync();
            final ServletInputStream in = request.getInputStream();
            final ServletOutputStream out = response.getOutputStream();
            
            in.setReadListener(new ReadListener()
            {
                @Override
                public void onError(Throwable t)
                {     
                    t.printStackTrace();
                }
                
                @Override
                public void onDataAvailable() throws IOException
                {
                    onDA.incrementAndGet();

                    boolean readF=false;
                    // Read all available content
                    while(in.isReady())
                    {
                        int c = in.read();
                        if (c<0)
                            throw new IllegalStateException();
                        if (c=='F')
                            readF=true;
                    }
                                        
                    if (readF)
                    {
                        onDA.set(0);

                        final byte[] buffer = new byte[64*1024];
                        Arrays.fill(buffer,(byte)'X');
                        for (int i=199;i<buffer.length;i+=200)
                            buffer[i]=(byte)'\n';

                        // Once we read block, let's make ourselves write blocked
                        out.setWriteListener(new WriteListener()
                        {
                            @Override
                            public void onWritePossible() throws IOException
                            {
                                onWP.incrementAndGet();

                                while (out.isReady())
                                    out.write(buffer);

                                try
                                {
                                    // As soon as we are write blocked, complete
                                    onWP.set(0);
                                    async.complete();
                                }
                                catch(Exception e)
                                {
                                    e.printStackTrace();
                                }
                                finally
                                {
                                    completed.countDown();
                                }
                            }

                            @Override
                            public void onError(Throwable t)
                            {
                                t.printStackTrace(); 
                            }
                        });
                    }
                }
                
                @Override
                public void onAllDataRead() throws IOException
                {        
                    throw new IllegalStateException();
                }
            });
            
        }
    }
    
    
}
