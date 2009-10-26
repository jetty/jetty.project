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
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.continuation.Continuation;
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
    protected QueuedThreadPool _threads=new QueuedThreadPool();
    protected boolean _stress;

    @Override
    protected void setUp() throws Exception
    {
        _stress= Boolean.getBoolean("STRESS");
        _threads.setMaxThreads(50);
        _server.setThreadPool(_threads);
        _connector = new SelectChannelConnector();
        _connector.setMaxIdleTime(120000);
        _server.setConnectors(new Connector[]{ _connector });
        _server.setHandler(_handler);
        _server.start();
        _port=_connector.getLocalPort();
        _addr=InetAddress.getLocalHost();
    }

    @Override
    protected void tearDown() throws Exception
    {
        _server.stop();
    }

    final static String[][] __paths = 
    {
        {"/path","NORMAL"},
        {"/path/info","NORMAL"},
        {"/path?sleep=<PERIOD>","SLEPT"},
        {"/path?suspend=<PERIOD>","TIMEOUT"},
        {"/path?suspend=60000&resume=<PERIOD>","RESUMED"},
        {"/path?suspend=60000&complete=<PERIOD>","COMPLETED"},
    };
    
    public void doConnections(int connections,final int loops) throws Throwable
    {
        Socket[] socket = new Socket[connections];
        int [][] path = new int[connections][loops];
        for (int i=0;i<connections;i++)
        {
            socket[i] = new Socket(_addr,_port);
            socket[i].setSoTimeout(30000);
            if (i%10==0)
                Thread.sleep(50);
            if (i%80==0)
                System.err.println();
            System.err.print('+');
        }
        System.err.println();
        Log.info("Bound "+connections);

        for (int l=0;l<loops;l++)
        {
            for (int i=0;i<connections;i++)
            {
                int p=path[i][l]=_random.nextInt(__paths.length);

                int period = _random.nextInt(290)+10;
                String uri=__paths[p][0].replace("<PERIOD>",Integer.toString(period));

                long start=System.currentTimeMillis();
                String request = 
                    "GET "+uri+" HTTP/1.1\r\n"+
                    "Host: localhost\r\n"+
                    "start: "+start+"\r\n"+
                    "result: "+__paths[p][1]+"\r\n"+
                    ((l+1<loops)?"":"Connection: close\r\n")+
                    "\r\n";
                socket[i].getOutputStream().write(request.getBytes("UTF-8"));
                socket[i].getOutputStream().flush();
            }
            if (l%80==0)
                System.err.println();
            System.err.print('.');
            Thread.sleep(_random.nextInt(290)+10);
        }

        System.err.println();
        Log.info("Sent "+(loops*__paths.length)+" requests");
        
        String[] results=new String[connections];
        for (int i=0;i<connections;i++)
        {
            results[i]=IO.toString(socket[i].getInputStream(),"UTF-8");
            if (i%80==0)
                System.err.println();
            System.err.print('-');
        }
        System.err.println();

        Log.info("Read "+connections+" connections");

        for (int i=0;i<connections;i++)
        {       
            int offset=0;
            String result=results[i];
            for (int l=0;l<loops;l++)
            {
                String expect = __paths[path[i][l]][1];
                expect=expect+" "+expect;
                
                offset=result.indexOf("200 OK",offset)+6;
                offset=result.indexOf("\r\n\r\n",offset)+4;
                int end=result.indexOf("\n",offset);
                String r=result.substring(offset,end).trim();
                assertEquals(i+","+l,expect,r);
                offset=end;
            }
        }
    }

    public void testAsync() throws Throwable
    {
        if (_stress)
        {
            System.err.println("STRESS!");
            doConnections(1600,240);
        }
        else
        {
            doConnections(80,80);
        }
    }
    
    private static class SuspendHandler extends HandlerWrapper
    {
        private final Timer _timer;
        
        public SuspendHandler()
        {
            _timer=new Timer();
        }
        
        @Override
        public void handle(String target, final Request baseRequest, final HttpServletRequest request, final HttpServletResponse response) throws IOException, ServletException
        {
            int read_before=0;
            long sleep_for=-1;
            long suspend_for=-1;
            long resume_after=-1;
            long complete_after=-1;
            
            final String uri=baseRequest.getUri().toString();
            
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
            
            if (DispatcherType.REQUEST.equals(baseRequest.getDispatcherType()))
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
                    final AsyncContext asyncContext = baseRequest.startAsync();
                    asyncContext.addContinuationListener(__asyncListener);
                    if (suspend_for>0)
                        asyncContext.setTimeout(suspend_for);
                   
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
                                    response.getOutputStream().println("COMPLETED " + request.getHeader("result"));
                                    baseRequest.setHandled(true);
                                    asyncContext.complete();
                                }
                                catch(Exception e)
                                {
                                    Request br=(Request)asyncContext.getRequest();
                                    System.err.println("\n"+e.toString());
                                    System.err.println(baseRequest+"=="+br);
                                    System.err.println(uri+"=="+br.getUri());
                                    System.err.println(asyncContext+"=="+br.getAsyncContinuation());
                                    
                                    Log.warn(e);
                                    System.exit(1);
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
                        response.getOutputStream().println("COMPLETED "+request.getHeader("result"));
                        baseRequest.setHandled(true);
                        asyncContext.complete();
                    }
                    else if (resume_after>0)
                    {
                        TimerTask resume = new TimerTask()
                        {
                            @Override
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
                    response.getOutputStream().println("SLEPT "+request.getHeader("result"));
                    baseRequest.setHandled(true);
                    return;
                }
                else
                {
                    response.setStatus(200);
                    response.getOutputStream().println("NORMAL "+request.getHeader("result"));
                    baseRequest.setHandled(true);
                    return;
                }
            }
            else if (request.getAttribute("TIMEOUT")!=null)
            {
                response.setStatus(200);
                response.getOutputStream().println("TIMEOUT "+request.getHeader("result"));
                baseRequest.setHandled(true);
            }
            else
            {
                response.setStatus(200);
                response.getOutputStream().println("RESUMED "+request.getHeader("result"));
                baseRequest.setHandled(true);
            }
        }
    }
    
    
    private static ContinuationListener __asyncListener = 
        new ContinuationListener()
    {
        public void onComplete(Continuation continuation)
        {
        }

        public void onTimeout(Continuation continuation)
        {
            continuation.setAttribute("TIMEOUT",Boolean.TRUE);
            continuation.resume();
        }
        
    };
}
