// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.util.Date;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.continuation.ContinuationEvent;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public class AsyncStressTest extends TestCase
{
    protected Server _server = new Server();
    protected SuspendHandler _handler = new SuspendHandler();
    protected SelectChannelConnector _connector;
    protected InetAddress _addr;
    protected int _port;
    protected Random _random = new Random();
    protected int[] _loops;
    protected QueuedThreadPool _threads=new QueuedThreadPool();
    protected boolean _stress;
    
    private static int STRESS_THREADS=500;

    protected void setUp() throws Exception
    {
        _stress= Boolean.getBoolean("STRESS");
        _threads.setMaxThreads(50);
        _server.setThreadPool(_threads);
        _connector = new SelectChannelConnector();
        _server.setConnectors(new Connector[]{ _connector });
        _server.setHandler(_handler);
        _server.start();
        _port=_connector.getLocalPort();
        _addr=Inet4Address.getLocalHost();
    }

    protected void tearDown() throws Exception
    {
        _server.stop();
    }

    final static String[][] __tests = 
    {
        {"/path","NORMAL"},
        {"/path?sleep=<TIMEOUT>","SLEPT"},
        {"/path?suspend=<TIMEOUT>","TIMEOUT"},
        {"/path?suspend=1000&resume=<TIMEOUT>","RESUMED"},
        {"/path?suspend=1000&complete=<TIMEOUT>","COMPLETED"},
    };
    
    
    public void doPaths(String name) throws Exception
    {
        for (int i=0;i<__tests.length;i++)
        {
            int timeout = _random.nextInt(200)+1;
            String uri=__tests[i][0].replace("<TIMEOUT>",Integer.toString(timeout));
            
            long start=System.currentTimeMillis();
            Socket socket = new Socket(_addr,_port);
            socket.setSoTimeout(30000);
            String request = "GET "+uri+" HTTP/1.0\r\n\r\n";
            socket.getOutputStream().write(request.getBytes());
            socket.getOutputStream().flush();
            String response = IO.toString(socket.getInputStream());
            socket.close();
            long end=System.currentTimeMillis();
            
            response=response.substring(response.indexOf("\r\n\r\n")+4);
            
            String test=name+"-"+i+" "+uri+" "+__tests[i][1];
            assertEquals(test,__tests[i][1],response);
            if (!response.equals("NORMAL"))
            {
                long duration=end-start;
                assertTrue(test+" "+duration,duration+50>=timeout);
            }
            
        }
    }
    
    public void doLoops(int thread, String name, int loops) throws Exception
    {
        try
        {
            for (int i=0;i<loops;i++)
            {
                _loops[thread]=i;
                doPaths(name+"-"+i);
                Thread.sleep(_random.nextInt(100));
            }
            _loops[thread]=loops;
        }
        catch(Exception e)
        {
            System.err.println(e);
            _connector.dump();
            _loops[thread]=-_loops[thread];
            throw e;
        }
    }
    
    public void doThreads(int threads,final int loops) throws Throwable
    {
        final Throwable[] throwable=new Throwable[threads];
        final Thread[] thread=new Thread[threads];
        for (int i=0;i<threads;i++)
        {
            final int id=i;
            final String name = "T"+i;
            thread[i]=new Thread()
            {
                public void run() 
                { 
                    try
                    {
                        doLoops(id,name,loops); 
                    }
                    catch(Throwable th)
                    {
                        th.printStackTrace();
                        throwable[id]=th;
                    }
                    finally
                    {
                    }
                }
            };
        }

        _loops=new int[threads];
        for (int i=0;i<threads;i++)
            thread[i].start();
        
        while(true)
        {
            Thread.sleep(1000L);
            int finished=0;
            int errors=0;
            int min=loops;
            int max=0;
            int total=0;
            for (int i=0;i<threads;i++)
            {
                int l=_loops[i];
                if (l<0)
                {
                    errors++;
                    total-=l;
                }
                else
                {
                    if (l<min)
                        min=l;
                    if (l>max)
                        max=l;
                    total+=l;
                    if (l==loops)
                        finished++;  
                }     
            }
            
            Log.info("min/ave/max/target="+min+"/"+(total/threads)+"/"+max+"/"+loops+" errors/finished/loops="+errors+"/"+finished+"/"+threads+" idle/threads="+(_threads.getIdleThreads()+_connector.getAcceptors())+"/"+_threads.getThreads());
            if ((finished+errors)==threads)
                break;
        }
        
        for (int i=0;i<threads;i++)
            thread[i].join();
        
        for (int i=0;i<threads;i++)
            if (throwable[i]!=null)
                throw throwable[i];
    }

    public void testAsync() throws Throwable
    {
        if (_stress)
        {
            System.err.println("STRESS! "+STRESS_THREADS);
            doThreads(STRESS_THREADS,200);
        }
        else
            doThreads(100,10);
        Thread.sleep(1000);
    }
    
    private static class SuspendHandler extends HandlerWrapper
    {
        private Timer _timer;
        
        public SuspendHandler()
        {
            _timer=new Timer();
        }
        
        public void handle(String target, final HttpServletRequest request, final HttpServletResponse response) throws IOException, ServletException
        {
            final Request base_request = (request instanceof Request)?((Request)request):HttpConnection.getCurrentConnection().getRequest();

            int read_before=0;
            long sleep_for=-1;
            long suspend_for=-1;
            long resume_after=-1;
            long complete_after=-1;
            
            if (request.getParameter("read")!=null)
                read_before=Integer.parseInt(request.getParameter("read"));
            if (request.getParameter("sleep")!=null)
                sleep_for=Integer.parseInt(request.getParameter("sleep"));
            if (request.getParameter("suspend")!=null)
                suspend_for=Integer.parseInt(request.getParameter("suspend"));
            if (request.getParameter("resume")!=null)
                resume_after=Integer.parseInt(request.getParameter("resume"));
            if (request.getParameter("complete")!=null)
                complete_after=Integer.parseInt(request.getParameter("complete"));
            
            if (DispatcherType.REQUEST.equals(base_request.getDispatcherType()))
            {
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

                if (suspend_for>=0)
                {
                    if (suspend_for>0)
                        base_request.setAsyncTimeout(suspend_for);
                    base_request.addEventListener(__asyncListener);
                    base_request.startAsync();
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
                    response.getOutputStream().print("SLEPT");
                    base_request.setHandled(true);
                    return;
                }
                else
                {
                    response.setStatus(200);
                    response.getOutputStream().print("NORMAL");
                    base_request.setHandled(true);
                    return;
                }
                
                final AsyncContext asyncContext = base_request.getAsyncContext();
                
                
                if (complete_after>0)
                {
                    TimerTask complete = new TimerTask()
                    {
                        public void run()
                        {
                            try
                            {
                                response.setStatus(200);
                                response.getOutputStream().print("COMPLETED");
                                base_request.setHandled(true);
                                asyncContext.complete();
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
                    response.getOutputStream().print("COMPLETED");
                    base_request.setHandled(true);
                    asyncContext.complete();
                }
                
                if (resume_after>0)
                {
                    TimerTask resume = new TimerTask()
                    {
                        public void run()
                        {
                            asyncContext.dispatch();
                        }
                    };
                    synchronized (_timer)
                    {
                        _timer.schedule(resume,resume_after);
                    }
                }
                else if (resume_after==0)
                {
                    asyncContext.dispatch();
                }
            }
            else if (request.getAttribute("TIMEOUT")!=null)
            {
                response.setStatus(200);
                response.getOutputStream().print("TIMEOUT");
                base_request.setHandled(true);
            }
            else
            {
                response.setStatus(200);
                response.getOutputStream().print("RESUMED");
                base_request.setHandled(true);
            }
        }
    }
    
    
    private static ContinuationListener __asyncListener = 
        new ContinuationListener()
    {
        public void onComplete(ContinuationEvent event) throws IOException
        {
        }

        public void onTimeout(ContinuationEvent event) throws IOException
        {
            event.getRequest().setAttribute("TIMEOUT",Boolean.TRUE);
            ((Request)event.getRequest()).getAsyncContext().dispatch();
        }
        
    };
}
