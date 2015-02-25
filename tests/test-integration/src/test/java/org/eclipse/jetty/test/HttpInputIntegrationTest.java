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

package org.eclipse.jetty.test;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.server.HttpChannelState;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.LocalConnector.LocalEndPoint;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.util.IO;
import org.hamcrest.Matchers;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class HttpInputIntegrationTest
{
    
    enum Mode { BLOCKING, ASYNC_DISPATCHED, ASYNC_OTHER_DISPATCHED, ASYNC_OTHER_WAIT }
    public final static String EOF = "__EOF__";
    public final static String DELAY = "__DELAY__";
    public final static String ABORT = "__ABORT__";
    
    private static Server __server;
    private static HttpConfiguration __config;
    
    @BeforeClass
    public static void beforeClass() throws Exception
    {
        __config = new HttpConfiguration();
        
        __server = new Server();
        __server.addConnector(new LocalConnector(__server,new HttpConnectionFactory(__config)));
        __server.addConnector(new ServerConnector(__server,new HttpConnectionFactory(__config),new HTTP2CServerConnectionFactory(__config)));
        
        ServletContextHandler context = new ServletContextHandler(__server,"/ctx");
        ServletHolder holder = new ServletHolder(new TestServlet());
        holder.setAsyncSupported(true);
        context.addServlet(holder,"/*");
        
        __server.start();
    }
    
    @AfterClass
    public static void afterClass() throws Exception
    {
        __server.stop();
    }
    
    interface TestClient
    {        
        /* ------------------------------------------------------------ */
        /**
         * @param uri The URI to test, typically /ctx/test?mode=THE_MODE
         * @param delayInFrame If null, send the request with no delays, if FALSE then send with delays between frames, if TRUE send with delays within frames
         * @param contentLength The content length header to send.
         * @param content The content to send, with each string to be converted to a chunk or a frame 
         * @return The response received in HTTP/1 format
         * @throws Exception
         */
        String send(String uri,Boolean delayInFrame, int contentLength, List<String> content) throws Exception; 
    }

    @Parameterized.Parameters
    public static Collection<Object[]> data()
    {
        List<Object[]> tests = new ArrayList<>();
        

        // TODO other client types!
        // test with the following clients/protocols:
        //   + Local
        //   + HTTP/1
        //   + SSL + HTTP/1
        //   + HTTP/2
        //   + SSL + HTTP/2
        //   + FASTCGI
        for (String c : new String[]{"LOCAL","HTTP/1"})
        {
            TestClient client;
            switch(c)
            {
                case "LOCAL":
                    client=new LocalClient();
                    break;
                case "HTTP/1":
                    client=new H1Client();
                    break;
                default:
                    throw new IllegalStateException();
            }

            // test async actions that are run:
            //   + By a thread in a container callback
            //   + By another thread while a container callback is active
            //   + By another thread while no container callback is active
            for (Mode mode: Mode.values())
            {

                // test servlet dispatch with:
                //   + Delayed dispatch on
                //   + Delayed dispatch off
                for (Boolean dispatch : new Boolean[]{false,true})
                {
                    // test send with 
                    //   + No delays between frames
                    //   + Delays between frames
                    //   + Delays within frames!
                    for (Boolean delayWithinFrame : new Boolean[]{null,false,true})
                    {
                        // test content 
                        // + unknown length + EOF
                        // + unknown length + content + EOF
                        // + unknown length + content + content + EOF
                        
                        // + known length + EOF
                        // + known length + content + EOF
                        // + known length + content + content + EOF
                        
                        tests.add(new Object[]{client,mode,dispatch,delayWithinFrame,200,0,-1,new String[]{}});
                        tests.add(new Object[]{client,mode,dispatch,delayWithinFrame,200,8,-1,new String[]{"content0"}});
                        tests.add(new Object[]{client,mode,dispatch,delayWithinFrame,200,16,-1,new String[]{"content0","CONTENT1"}});
                        
                        tests.add(new Object[]{client,mode,dispatch,delayWithinFrame,200,0,0,new String[]{}});
                        tests.add(new Object[]{client,mode,dispatch,delayWithinFrame,200,8,8,new String[]{"content0"}});
                        tests.add(new Object[]{client,mode,dispatch,delayWithinFrame,200,16,16,new String[]{"content0","CONTENT1"}});
                        
                    }
                }
            }
        }
        return tests;
    }
    

    final TestClient _client;
    final Mode _mode;
    final Boolean _delay;
    final int _status;
    final int _read;
    final int _length;
    final List<String> _send;
    
    public HttpInputIntegrationTest(TestClient client, Mode mode,boolean dispatch,Boolean delay,int status,int read,int length,String... send)
    {
        _client=client;
        _mode=mode;
        __config.setDelayDispatchUntilContent(dispatch);
        _delay=delay;
        _status=status;
        _read=read;
        _length=length;
        _send = Arrays.asList(send);
    }
    
    
    private static void runmode(Mode mode,final Request request, final Runnable test)
    {
        switch(mode)
        {
            case ASYNC_DISPATCHED:
            {
                test.run();
                break;
            }   
            case ASYNC_OTHER_DISPATCHED:
            {
                final CountDownLatch latch = new CountDownLatch(1);
                new Thread()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            test.run();
                        }
                        finally
                        {
                            latch.countDown();
                        }
                    }
                }.start();
                // prevent caller returning until other thread complete
                try
                {
                    if (!latch.await(5,TimeUnit.SECONDS))
                        Assert.fail();
                }
                catch(Exception e)
                {
                    Assert.fail();
                }
                break;
            }   
            case ASYNC_OTHER_WAIT:
            {
                final CountDownLatch latch = new CountDownLatch(1);
                final HttpChannelState.State S=request.getHttpChannelState().getState();
                new Thread()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            if (!latch.await(5,TimeUnit.SECONDS))
                                Assert.fail();
                            
                            // Spin until state change
                            HttpChannelState.State s=request.getHttpChannelState().getState();
                            while(request.getHttpChannelState().getState()==S )
                            {
                                Thread.yield();
                                s=request.getHttpChannelState().getState();
                            }
                            test.run();
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                    }
                }.start();
                // ensure other thread running before trying to return
                latch.countDown();
                break;
            }
                
            default:
                throw new IllegalStateException();
        }
        
    }
    
    
    @Test
    @Slow
    public void test() throws Exception
    {
        System.err.printf("TEST c=%s, m=%s, d=%b D=%s content-length:%d expect=%d read=%d content:%s%n",_client.getClass().getSimpleName(),_mode,__config.isDelayDispatchUntilContent(),_delay,_length,_status,_read,_send);

        String response = _client.send("/ctx/test?mode="+_mode,_delay,_length,_send);
        
        assertThat(response,startsWith("HTTP"));
        assertThat(response,Matchers.containsString(" "+_status+" "));
        assertThat(response,Matchers.containsString("read="+_read));
    }

    
    
    public static class TestServlet extends HttpServlet
    {

        @Override
        protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException
        {
            final Mode mode = Mode.valueOf(req.getParameter("mode"));
            resp.setContentType("text/plain");
                        
            if (mode==Mode.BLOCKING)
            {
                try
                {
                    String content = IO.toString(req.getInputStream());
                    resp.setStatus(200);
                    resp.setContentType("text/plain");
                    resp.getWriter().println("read="+content.length());
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                    resp.setStatus(500);
                    resp.getWriter().println("read="+e);
                }
            }
            else
            {
                // we are asynchronous
                final AsyncContext context = req.startAsync();
                context.setTimeout(10000);
                final ServletInputStream in = req.getInputStream();
                final Request request = Request.getBaseRequest(req);
                final AtomicInteger read = new AtomicInteger(0);

                runmode(mode,request,new Runnable()
                {
                    @Override
                    public void run()
                    {
                        in.setReadListener(new ReadListener()
                        {
                            @Override
                            public void onError(Throwable t)
                            {
                                t.printStackTrace();
                                try
                                {
                                    resp.sendError(500);
                                }
                                catch (IOException e)
                                {
                                    e.printStackTrace();
                                    throw new RuntimeException(e);
                                }
                                context.complete();
                            }

                            @Override
                            public void onDataAvailable() throws IOException
                            {
                                runmode(mode,request,new Runnable()
                                {
                                    @Override
                                    public void run()
                                    {
                                        while(in.isReady() && !in.isFinished())
                                        {
                                            try
                                            {
                                                int b = in.read();
                                                if (b<0)
                                                    return;
                                                read.incrementAndGet();
                                            }
                                            catch (IOException e)
                                            {
                                                onError(e);
                                            }
                                        }
                                    }
                                });
                            }

                            @Override
                            public void onAllDataRead() throws IOException
                            {
                                resp.setStatus(200);
                                resp.setContentType("text/plain");
                                resp.getWriter().println("read="+read.get());
                                context.complete();
                            }
                        });
                    }
                });
            }
        }
    }
    

    public static class H1Client implements TestClient
    {

        @Override
        public String send(String uri, Boolean delayInFrame,int contentLength, List<String> content) throws Exception
        {
            int port=((NetworkConnector)__server.getConnectors()[1]).getLocalPort();
            
            try (Socket client = new Socket("localhost", port))
            {
                client.setSoTimeout(5000);
                OutputStream out = client.getOutputStream();

                StringBuilder buffer = new StringBuilder();
                buffer.append("GET ").append(uri).append(" HTTP/1.1\r\n");
                buffer.append("Host: localhost:").append(port).append("\r\n");
                buffer.append("Connection: close\r\n");

                flush(out,buffer,delayInFrame,true);

                boolean chunked=contentLength<0;
                if (chunked)
                    buffer.append("Transfer-Encoding: chunked\r\n");
                else
                    buffer.append("Content-Length: ").append(contentLength).append("\r\n");
                    
                if (contentLength>0)
                    buffer.append("Content-Type: text/plain\r\n");
                buffer.append("\r\n");

                flush(out,buffer,delayInFrame,false);
                
                for (String c : content)
                {
                    if (chunked)
                    {
                        buffer.append("\r\n").append(Integer.toHexString(c.length())).append("\r\n");
                       flush(out,buffer,delayInFrame,true);
                    }
                    
                    buffer.append(c.substring(0,1));
                    flush(out,buffer,delayInFrame,true);
                    buffer.append(c.substring(1));
                    flush(out,buffer,delayInFrame,false);
                }

                if (chunked)
                {
                    buffer.append("\r\n0");
                    flush(out,buffer,delayInFrame,true);
                    buffer.append("\r\n\r\n");
                }
                
                flush(out,buffer);
                
                return IO.toString(client.getInputStream());
            }
            
        }

        private void flush(OutputStream out, StringBuilder buffer, Boolean delayInFrame, boolean inFrame) throws Exception
        {
            // Flush now if we should delay
            if (delayInFrame!=null && delayInFrame.equals(inFrame))
            {
                flush(out,buffer);
            }
        }
        
        private void flush(OutputStream out, StringBuilder buffer) throws Exception
        {
            String flush=buffer.toString();
            buffer.setLength(0);
            out.write(flush.getBytes(StandardCharsets.ISO_8859_1));
            Thread.sleep(50);
        }

    }

    public static class LocalClient implements TestClient
    {
       
        @Override
        public String send(String uri, Boolean delayInFrame,int contentLength, List<String> content) throws Exception
        {
            LocalConnector connector = __server.getBean(LocalConnector.class);

            StringBuilder buffer = new StringBuilder();
            buffer.append("GET ").append(uri).append(" HTTP/1.1\r\n");
            buffer.append("Host: localhost\r\n");
            buffer.append("Connection: close\r\n");

            LocalEndPoint local = connector.executeRequest("");
            
            flush(local,buffer,delayInFrame,true);

            boolean chunked=contentLength<0;
            if (chunked)
                buffer.append("Transfer-Encoding: chunked\r\n");
            else
                buffer.append("Content-Length: ").append(contentLength).append("\r\n");

            if (contentLength>0)
                buffer.append("Content-Type: text/plain\r\n");
            buffer.append("\r\n");

            flush(local,buffer,delayInFrame,false);

            for (String c : content)
            {
                if (chunked)
                {
                    buffer.append("\r\n").append(Integer.toHexString(c.length())).append("\r\n");
                    flush(local,buffer,delayInFrame,true);
                }

                buffer.append(c.substring(0,1));
                flush(local,buffer,delayInFrame,true);
                buffer.append(c.substring(1));
                if (chunked)
                    buffer.append("\r\n"); 
                flush(local,buffer,delayInFrame,false);
            }

            if (chunked)
            {
                buffer.append("0");
                flush(local,buffer,delayInFrame,true);
                buffer.append("\r\n\r\n");
            }

            flush(local,buffer);
            local.waitUntilClosed();
            return local.takeOutputString();
        }
          

        private void flush(LocalEndPoint local, StringBuilder buffer, Boolean delayInFrame, boolean inFrame) throws Exception
        {
            // Flush now if we should delay
            if (delayInFrame!=null && delayInFrame.equals(inFrame))
            {
                flush(local,buffer);
            }
        }
        
        private void flush(final LocalEndPoint local, StringBuilder buffer) throws Exception
        {
            final String flush=buffer.toString();
            buffer.setLength(0);
            // System.err.println("FLUSH:'"+flush+"'");
            new Thread()
            {
                @Override
                public void run()
                {
                    local.addInput(flush);
                }
            }.start();
            Thread.sleep(50);
        }

    }
    
}
