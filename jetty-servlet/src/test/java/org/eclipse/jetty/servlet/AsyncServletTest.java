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

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.ServletRequestWrapper;
import javax.servlet.ServletResponseWrapper;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.toolchain.test.AdvancedRunner;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;


@RunWith(AdvancedRunner.class)
public class AsyncServletTest
{
    protected AsyncServlet _servlet=new AsyncServlet();
    protected int _port;

    protected Server _server = new Server();
    protected ServletHandler _servletHandler;
    protected ServerConnector _connector;
    protected List<String> _log;
    protected int _expectedLogs;
    protected String _expectedCode;

    @Before
    public void setUp() throws Exception
    {
        _connector = new ServerConnector(_server);
        _server.setConnectors(new Connector[]{ _connector });
        
        _log=new ArrayList<>();
        RequestLog log=new Log();
        RequestLogHandler logHandler = new RequestLogHandler();
        logHandler.setRequestLog(log);
        _server.setHandler(logHandler);
        _expectedLogs=1;
        _expectedCode="200 ";

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/ctx");
        logHandler.setHandler(context);
        
        _servletHandler=context.getServletHandler();
        ServletHolder holder=new ServletHolder(_servlet);
        holder.setAsyncSupported(true);
        _servletHandler.addServletWithMapping(holder,"/path/*");
        _servletHandler.addServletWithMapping(holder,"/path1/*");
        _servletHandler.addServletWithMapping(holder,"/path2/*");
        _servletHandler.addServletWithMapping(new ServletHolder(new FwdServlet()),"/fwd/*");
        _server.start();
        _port=_connector.getLocalPort();
    }

    @After
    public void tearDown() throws Exception
    {
        assertEquals(_expectedLogs,_log.size());
        Assert.assertThat(_log.get(0), Matchers.containsString(_expectedCode));
        _server.stop();
    }

    @Test
    public void testNormal() throws Exception
    {
        String response=process(null,null);
        assertEquals("HTTP/1.1 200 OK",response.substring(0,15));
        assertContains(
                "history: REQUEST /path\r\n"+
                "history: initial\r\n",response);
        assertContains("NORMAL",response);
        assertNotContains("history: onTimeout",response);
        assertNotContains("history: onComplete",response);
    }

    @Test
    public void testSleep() throws Exception
    {
        String response=process("sleep=200",null);
        assertEquals("HTTP/1.1 200 OK",response.substring(0,15));
        assertContains(
                "history: REQUEST /path\r\n"+
                "history: initial\r\n",response);
        assertContains("SLEPT",response);
        assertNotContains("history: onTimeout",response);
        assertNotContains("history: onComplete",response);
    }

    @Test
    public void testSuspend() throws Exception
    {
        _expectedCode="500 ";
        String response=process("suspend=200",null);
        assertEquals("HTTP/1.1 500 Async Timeout",response.substring(0,26));
        assertContains(
            "history: REQUEST /path\r\n"+
            "history: initial\r\n"+
            "history: suspend\r\n"+
            "history: onTimeout\r\n"+
            "history: ERROR /path\r\n"+
            "history: !initial\r\n"+
            "history: onComplete\r\n",response);

        assertContains("ERROR: /ctx/path/info",response);
    }

    @Test
    public void testSuspendOnTimeoutDispatch() throws Exception
    {
        String response=process("suspend=200&timeout=dispatch",null);
        assertEquals("HTTP/1.1 200 OK",response.substring(0,15));
        assertContains(
            "history: REQUEST /path\r\n"+
            "history: initial\r\n"+
            "history: suspend\r\n"+
            "history: onTimeout\r\n"+
            "history: dispatch\r\n"+
            "history: ASYNC /path\r\n"+
            "history: !initial\r\n"+
            "history: onComplete\r\n",response);

        assertContains("DISPATCHED",response);
    }

    @Test
    public void testSuspendOnTimeoutComplete() throws Exception
    {
        String response=process("suspend=200&timeout=complete",null);
        assertEquals("HTTP/1.1 200 OK",response.substring(0,15));
        assertContains(
            "history: REQUEST /path\r\n"+
            "history: initial\r\n"+
            "history: suspend\r\n"+
            "history: onTimeout\r\n"+
            "history: complete\r\n"+
            "history: onComplete\r\n",response);

        assertContains("COMPLETED",response);
    }

    @Test
    public void testSuspendWaitResume() throws Exception
    {
        String response=process("suspend=200&resume=10",null);
        assertEquals("HTTP/1.1 200 OK",response.substring(0,15));
        assertContains(
            "history: REQUEST /path\r\n"+
            "history: initial\r\n"+
            "history: suspend\r\n"+
            "history: resume\r\n"+
            "history: ASYNC /path\r\n"+
            "history: !initial\r\n"+
            "history: onComplete\r\n",response);
        assertNotContains("history: onTimeout",response);
    }

    @Test
    public void testSuspendResume() throws Exception
    {
        String response=process("suspend=200&resume=0",null);
        assertEquals("HTTP/1.1 200 OK",response.substring(0,15));
        assertContains(
            "history: REQUEST /path\r\n"+
            "history: initial\r\n"+
            "history: suspend\r\n"+
            "history: resume\r\n"+
            "history: ASYNC /path\r\n"+
            "history: !initial\r\n"+
            "history: onComplete\r\n",response);
        assertContains("history: onComplete",response);
    }

    @Test
    public void testSuspendWaitComplete() throws Exception
    {
        String response=process("suspend=200&complete=50",null);
        assertEquals("HTTP/1.1 200 OK",response.substring(0,15));
        assertContains(
            "history: REQUEST /path\r\n"+
            "history: initial\r\n"+
            "history: suspend\r\n"+
            "history: complete\r\n"+
            "history: onComplete\r\n",response);
        assertContains("COMPLETED",response);
        assertNotContains("history: onTimeout",response);
        assertNotContains("history: !initial",response);
    }

    @Test
    public void testSuspendComplete() throws Exception
    {
        String response=process("suspend=200&complete=0",null);
        assertEquals("HTTP/1.1 200 OK",response.substring(0,15));
        assertContains(
            "history: REQUEST /path\r\n"+
            "history: initial\r\n"+
            "history: suspend\r\n"+
            "history: complete\r\n"+
            "history: onComplete\r\n",response);
        assertContains("COMPLETED",response);
        assertNotContains("history: onTimeout",response);
        assertNotContains("history: !initial",response);
    }

    @Test
    public void testSuspendWaitResumeSuspendWaitResume() throws Exception
    {
        String response=process("suspend=1000&resume=10&suspend2=1000&resume2=10",null);
        assertEquals("HTTP/1.1 200 OK",response.substring(0,15));
        assertContains(
            "history: REQUEST /path\r\n"+
            "history: initial\r\n"+
            "history: suspend\r\n"+
            "history: resume\r\n"+
            "history: ASYNC /path\r\n"+
            "history: !initial\r\n"+
            "history: suspend\r\n"+
            "history: resume\r\n"+
            "history: ASYNC /path\r\n"+
            "history: !initial\r\n"+
            "history: onComplete\r\n",response);
        assertContains("DISPATCHED",response);
    }

    @Test
    public void testSuspendWaitResumeSuspendComplete() throws Exception
    {
        String response=process("suspend=1000&resume=10&suspend2=1000&complete2=10",null);
        assertEquals("HTTP/1.1 200 OK",response.substring(0,15));
        assertContains(
            "history: REQUEST /path\r\n"+
            "history: initial\r\n"+
            "history: suspend\r\n"+
            "history: resume\r\n"+
            "history: ASYNC /path\r\n"+
            "history: !initial\r\n"+
            "history: suspend\r\n"+
            "history: complete\r\n"+
            "history: onComplete\r\n",response);
        assertContains("COMPLETED",response);
    }

    @Test
    public void testSuspendWaitResumeSuspend() throws Exception
    {
        _expectedCode="500 ";
        String response=process("suspend=1000&resume=10&suspend2=10",null);
        assertEquals("HTTP/1.1 500 Async Timeout",response.substring(0,26));
        assertContains(
            "history: REQUEST /path\r\n"+
            "history: initial\r\n"+
            "history: suspend\r\n"+
            "history: resume\r\n"+
            "history: ASYNC /path\r\n"+
            "history: !initial\r\n"+
            "history: suspend\r\n"+
            "history: onTimeout\r\n"+
            "history: ERROR /path\r\n"+
            "history: !initial\r\n"+
            "history: onComplete\r\n",response);
        assertContains("ERROR: /ctx/path/info",response);
    }

    @Test
    public void testSuspendTimeoutSuspendResume() throws Exception
    {
        String response=process("suspend=10&suspend2=1000&resume2=10",null);
        assertEquals("HTTP/1.1 200 OK",response.substring(0,15));
        assertContains(
            "history: REQUEST /path\r\n"+
            "history: initial\r\n"+
            "history: suspend\r\n"+
            "history: onTimeout\r\n"+
            "history: ERROR /path\r\n"+
            "history: !initial\r\n"+
            "history: suspend\r\n"+
            "history: resume\r\n"+
            "history: ASYNC /path\r\n"+
            "history: !initial\r\n"+
            "history: onComplete\r\n",response);
        assertContains("DISPATCHED",response);
    }

    @Test
    public void testSuspendTimeoutSuspendComplete() throws Exception
    {
        String response=process("suspend=10&suspend2=1000&complete2=10",null);
        assertEquals("HTTP/1.1 200 OK",response.substring(0,15));
        assertContains(
            "history: REQUEST /path\r\n"+
            "history: initial\r\n"+
            "history: suspend\r\n"+
            "history: onTimeout\r\n"+
            "history: ERROR /path\r\n"+
            "history: !initial\r\n"+
            "history: suspend\r\n"+
            "history: complete\r\n"+
            "history: onComplete\r\n",response);
        assertContains("COMPLETED",response);
    }

    @Test
    public void testSuspendTimeoutSuspend() throws Exception
    {
        _expectedCode="500 ";
        String response=process("suspend=10&suspend2=10",null);
        assertContains(
            "history: REQUEST /path\r\n"+
            "history: initial\r\n"+
            "history: suspend\r\n"+
            "history: onTimeout\r\n"+
            "history: ERROR /path\r\n"+
            "history: !initial\r\n"+
            "history: suspend\r\n"+
            "history: onTimeout\r\n"+
            "history: ERROR /path\r\n"+
            "history: !initial\r\n"+
            "history: onComplete\r\n",response);
        assertContains("ERROR: /ctx/path/info",response);
    }

    @Test
    public void testWrapStartDispatch() throws Exception
    {
        String response=process("wrap=true&suspend=200&resume=20",null);
        assertEquals("HTTP/1.1 200 OK",response.substring(0,15));
        assertContains(
            "history: REQUEST /path\r\n"+
            "history: initial\r\n"+
            "history: suspend\r\n"+
            "history: resume\r\n"+
            "history: ASYNC /path\r\n"+
            "history: wrapped REQ RSP\r\n"+
            "history: !initial\r\n"+
            "history: onComplete\r\n",response);
        assertContains("DISPATCHED",response);
    }

    @Test
    public void testFwdStartDispatch() throws Exception
    {
        String response=process("fwd","suspend=200&resume=20",null);
        assertEquals("HTTP/1.1 200 OK",response.substring(0,15));
        assertContains(
            "history: FWD REQUEST /fwd\r\n"+
            "history: FORWARD /path1\r\n"+
            "history: initial\r\n"+
            "history: suspend\r\n"+
            "history: resume\r\n"+
            "history: FWD ASYNC /fwd\r\n"+
            "history: FORWARD /path1\r\n"+
            "history: !initial\r\n"+
            "history: onComplete\r\n",response);
        assertContains("DISPATCHED",response);
    }
    
    @Test
    public void testFwdStartDispatchPath() throws Exception
    {
        String response=process("fwd","suspend=200&resume=20&path=/path2",null);
        assertEquals("HTTP/1.1 200 OK",response.substring(0,15));
        assertContains(
            "history: FWD REQUEST /fwd\r\n"+
            "history: FORWARD /path1\r\n"+
            "history: initial\r\n"+
            "history: suspend\r\n"+
            "history: resume\r\n"+
            "history: ASYNC /path2\r\n"+
            "history: !initial\r\n"+
            "history: onComplete\r\n",response);
        assertContains("DISPATCHED",response);
    }

    @Test
    public void testFwdWrapStartDispatch() throws Exception
    {
        String response=process("fwd","wrap=true&suspend=200&resume=20",null);
        assertEquals("HTTP/1.1 200 OK",response.substring(0,15));
        assertContains(
            "history: FWD REQUEST /fwd\r\n"+
            "history: FORWARD /path1\r\n"+
            "history: initial\r\n"+
            "history: suspend\r\n"+
            "history: resume\r\n"+
            "history: ASYNC /path1\r\n"+
            "history: wrapped REQ RSP\r\n"+
            "history: !initial\r\n"+
            "history: onComplete\r\n",response);
        assertContains("DISPATCHED",response);
    }
    
    @Test
    public void testFwdWrapStartDispatchPath() throws Exception
    {
        String response=process("fwd","wrap=true&suspend=200&resume=20&path=/path2",null);
        assertEquals("HTTP/1.1 200 OK",response.substring(0,15));
        assertContains(
            "history: FWD REQUEST /fwd\r\n"+
            "history: FORWARD /path1\r\n"+
            "history: initial\r\n"+
            "history: suspend\r\n"+
            "history: resume\r\n"+
            "history: ASYNC /path2\r\n"+
            "history: wrapped REQ RSP\r\n"+
            "history: !initial\r\n"+
            "history: onComplete\r\n",response);
        assertContains("DISPATCHED",response);
    }
    
    
    @Test
    public void testAsyncRead() throws Exception
    {
        _expectedLogs=2;
        String header="GET /ctx/path/info?suspend=2000&resume=1500 HTTP/1.1\r\n"+
            "Host: localhost\r\n"+
            "Content-Length: 10\r\n"+
            "\r\n";
        String body="12345678\r\n";
        String close="GET /ctx/path/info HTTP/1.1\r\n"+
            "Host: localhost\r\n"+
            "Connection: close\r\n"+
            "\r\n";

        try (Socket socket = new Socket("localhost",_port))
        {
            socket.setSoTimeout(10000);
            socket.getOutputStream().write(header.getBytes(StandardCharsets.ISO_8859_1));
            Thread.sleep(500);
            socket.getOutputStream().write(body.getBytes(StandardCharsets.ISO_8859_1),0,2);
            Thread.sleep(500);
            socket.getOutputStream().write(body.getBytes(StandardCharsets.ISO_8859_1),2,8);
            socket.getOutputStream().write(close.getBytes(StandardCharsets.ISO_8859_1));

            String response = IO.toString(socket.getInputStream());
            assertEquals("HTTP/1.1 200 OK",response.substring(0,15));
            assertContains(
                "history: REQUEST /path\r\n"+
                "history: initial\r\n"+
                "history: suspend\r\n"+
                "history: async-read=10\r\n"+
                "history: resume\r\n"+
                "history: ASYNC /path\r\n"+
                "history: !initial\r\n"+
                "history: onComplete\r\n",response);
        }
    }

    public synchronized String process(String query,String content) throws Exception
    {
        return process("path",query,content);
    }
    
    public synchronized String process(String path,String query,String content) throws Exception
    {
        String request = "GET /ctx/"+path+"/info";

        if (query!=null)
            request+="?"+query;
        request+=" HTTP/1.1\r\n"+
        "Host: localhost\r\n"+
        "Connection: close\r\n";
        if (content==null)
            request+="\r\n";
        else
        {
            request+="Content-Length: "+content.length()+"\r\n";
            request+="\r\n" + content;
        }

        int port=_port;
        try (Socket socket = new Socket("localhost",port))
        {
            socket.setSoTimeout(1000000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            return IO.toString(socket.getInputStream());
        }
        catch(Exception e)
        {
            System.err.println("failed on port "+port);
            e.printStackTrace();
            throw e;
        }
    }

    protected void assertContains(String content,String response)
    {
        Assert.assertThat(response, Matchers.containsString(content));
    }

    protected void assertNotContains(String content,String response)
    {
        Assert.assertThat(response,Matchers.not(Matchers.containsString(content)));
    }

    private static class FwdServlet extends HttpServlet
    {
        @Override
        public void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
        {
            response.addHeader("history","FWD "+request.getDispatcherType()+" "+request.getServletPath());
            if (request instanceof ServletRequestWrapper || response instanceof ServletResponseWrapper)
                response.addHeader("history","wrapped"+((request instanceof ServletRequestWrapper)?" REQ":"")+((response instanceof ServletResponseWrapper)?" RSP":""));
            request.getServletContext().getRequestDispatcher("/path1").forward(request,response);
        }
    }
    
    private static class AsyncServlet extends HttpServlet
    {
        private static final long serialVersionUID = -8161977157098646562L;
        private final Timer _timer=new Timer();

        @Override
        public void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
        {
            // System.err.println(request.getDispatcherType()+" "+request.getRequestURI());
            response.addHeader("history",request.getDispatcherType()+" "+request.getServletPath());
            if (request instanceof ServletRequestWrapper || response instanceof ServletResponseWrapper)
                response.addHeader("history","wrapped"+((request instanceof ServletRequestWrapper)?" REQ":"")+((response instanceof ServletResponseWrapper)?" RSP":""));
                
            boolean wrap="true".equals(request.getParameter("wrap"));
            int read_before=0;
            long sleep_for=-1;
            long suspend_for=-1;
            long suspend2_for=-1;
            long resume_after=-1;
            long resume2_after=-1;
            long complete_after=-1;
            long complete2_after=-1;

            
            if (request.getParameter("read")!=null)
                read_before=Integer.parseInt(request.getParameter("read"));
            if (request.getParameter("sleep")!=null)
                sleep_for=Integer.parseInt(request.getParameter("sleep"));
            if (request.getParameter("suspend")!=null)
                suspend_for=Integer.parseInt(request.getParameter("suspend"));
            if (request.getParameter("suspend2")!=null)
                suspend2_for=Integer.parseInt(request.getParameter("suspend2"));
            if (request.getParameter("resume")!=null)
                resume_after=Integer.parseInt(request.getParameter("resume"));
            final String path=request.getParameter("path");
            if (request.getParameter("resume2")!=null)
                resume2_after=Integer.parseInt(request.getParameter("resume2"));
            if (request.getParameter("complete")!=null)
                complete_after=Integer.parseInt(request.getParameter("complete"));
            if (request.getParameter("complete2")!=null)
                complete2_after=Integer.parseInt(request.getParameter("complete2"));

            if (request.getAttribute("State")==null)
            {
                request.setAttribute("State",new Integer(1));
                response.addHeader("history","initial");
                if (read_before>0)
                {
                    byte[] buf=new byte[read_before];
                    request.getInputStream().read(buf);
                }
                else if (read_before<0)
                {
                    InputStream in = request.getInputStream();
                    int b=in.read();
                    while(b!=-1)
                        b=in.read();
                }
                else if (request.getContentLength()>0)
                {
                    new Thread()
                    {
                        @Override
                        public void run()
                        {
                            int c=0;
                            try
                            {
                                InputStream in=request.getInputStream();
                                int b=0;
                                while(b!=-1)
                                    if((b=in.read())>=0)
                                        c++;
                                response.addHeader("history","async-read="+c);
                            }
                            catch(Exception e)
                            {
                                e.printStackTrace();
                            }
                        }
                    }.start();
                }

                if (suspend_for>=0)
                {
                    final AsyncContext async=wrap?request.startAsync(new HttpServletRequestWrapper(request),new HttpServletResponseWrapper(response)):request.startAsync();
                    if (suspend_for>0)
                        async.setTimeout(suspend_for);
                    async.addListener(__listener);
                    response.addHeader("history","suspend");

                    if (complete_after>0)
                    {
                        TimerTask complete = new TimerTask()
                        {
                            @Override
                            public void run()
                            {
                                try
                                {
                                    response.setStatus(200);
                                    response.getOutputStream().println("COMPLETED\n");
                                    response.addHeader("history","complete");
                                    async.complete();
                                }
                                catch(Exception e)
                                {
                                    e.printStackTrace();
                                }
                            }
                        };
                        synchronized (_timer)
                        {
                            _timer.schedule(complete,complete_after);
                        }
                    }
                    else if (complete_after==0)
                    {
                        response.setStatus(200);
                        response.getOutputStream().println("COMPLETED\n");
                        response.addHeader("history","complete");
                        async.complete();
                    }
                    else if (resume_after>0)
                    {
                        TimerTask resume = new TimerTask()
                        {
                            @Override
                            public void run()
                            {
                                ((HttpServletResponse)async.getResponse()).addHeader("history","resume");
                                if (path!=null)             
                                    async.dispatch(path);
                                else
                                    async.dispatch();
                            }
                        };
                        synchronized (_timer)
                        {
                            _timer.schedule(resume,resume_after);
                        }
                    }
                    else if (resume_after==0)
                    {
                        ((HttpServletResponse)async.getResponse()).addHeader("history","resume");
                        if (path!=null)             
                            async.dispatch(path);
                        else
                            async.dispatch();
                    }

                }
                else if (sleep_for>=0)
                {
                    try
                    {
                        Thread.sleep(sleep_for);
                    }
                    catch (InterruptedException e)
                    {
                        e.printStackTrace();
                    }
                    response.setStatus(200);
                    response.getOutputStream().println("SLEPT\n");
                }
                else
                {
                    response.setStatus(200);
                    response.getOutputStream().println("NORMAL\n");
                }
            }
            else
            {
                response.addHeader("history","!initial");

                if (suspend2_for>=0 && request.getAttribute("2nd")==null)
                {
                    final AsyncContext async=wrap?request.startAsync(new HttpServletRequestWrapper(request),new HttpServletResponseWrapper(response)):request.startAsync();
                    async.addListener(__listener);
                    request.setAttribute("2nd","cycle");

                    if (suspend2_for>0)
                    {
                        async.setTimeout(suspend2_for);
                    }
                    // continuation.addContinuationListener(__listener);
                    response.addHeader("history","suspend");

                    if (complete2_after>0)
                    {
                        TimerTask complete = new TimerTask()
                        {
                            @Override
                            public void run()
                            {
                                try
                                {
                                    response.setStatus(200);
                                    response.getOutputStream().println("COMPLETED\n");
                                    response.addHeader("history","complete");
                                    async.complete();
                                }
                                catch(Exception e)
                                {
                                    e.printStackTrace();
                                }
                            }
                        };
                        synchronized (_timer)
                        {
                            _timer.schedule(complete,complete2_after);
                        }
                    }
                    else if (complete2_after==0)
                    {
                        response.setStatus(200);
                        response.getOutputStream().println("COMPLETED\n");
                        response.addHeader("history","complete");
                        async.complete();
                    }
                    else if (resume2_after>0)
                    {
                        TimerTask resume = new TimerTask()
                        {
                            @Override
                            public void run()
                            {
                                response.addHeader("history","resume");
                                async.dispatch();
                            }
                        };
                        synchronized (_timer)
                        {
                            _timer.schedule(resume,resume2_after);
                        }
                    }
                    else if (resume2_after==0)
                    {
                        response.addHeader("history","dispatch");
                        async.dispatch();
                    }
                }
                else if(request.getDispatcherType()==DispatcherType.ERROR)
                {
                    response.getOutputStream().println("ERROR: "+request.getContextPath()+request.getServletPath()+request.getPathInfo());
                }
                else
                {
                    response.setStatus(200);
                    response.getOutputStream().println("DISPATCHED");
                }
            }
        }
    }


    private static AsyncListener __listener = new AsyncListener()
    {
        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
            ((HttpServletResponse)event.getSuppliedResponse()).addHeader("history","onTimeout");
            String action=event.getSuppliedRequest().getParameter("timeout");
            if (action!=null)
            {
                ((HttpServletResponse)event.getSuppliedResponse()).addHeader("history",action);
                if ("dispatch".equals(action))
                    event.getAsyncContext().dispatch();
                if ("complete".equals(action))
                {
                    event.getSuppliedResponse().getOutputStream().println("COMPLETED\n");
                    event.getAsyncContext().complete();
                }
            }
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException
        {
        }

        @Override
        public void onError(AsyncEvent event) throws IOException
        {
        }

        @Override
        public void onComplete(AsyncEvent event) throws IOException
        {
            ((HttpServletResponse)event.getSuppliedResponse()).addHeader("history","onComplete");
        }
    };

    class Log extends AbstractLifeCycle implements RequestLog
    {
        @Override
        public void log(Request request, Response response)
        {            
            _log.add(response.getStatus()+" "+response.getContentCount()+" "+request.getRequestURI());
        }
    }
}
