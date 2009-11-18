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

package org.eclipse.jetty.continuation.test;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.Timer;
import java.util.TimerTask;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationSupport;



public abstract class ContinuationBase extends TestCase
{
    protected SuspendServlet _servlet=new SuspendServlet();
    protected int _port;
    
    protected void doNormal(String type) throws Exception
    {
        String response=process(null,null);
        assertContains(type,response);
        assertContains("NORMAL",response);
        assertNotContains("history: onTimeout",response);
        assertNotContains("history: onComplete",response);
    }

    protected void doSleep() throws Exception
    {
        String response=process("sleep=200",null);
        assertContains("SLEPT",response);
        assertNotContains("history: onTimeout",response);
        assertNotContains("history: onComplete",response);
    }

    protected void doSuspend() throws Exception
    {
        String response=process("suspend=200",null);
        assertContains("TIMEOUT",response);
        assertContains("history: onTimeout",response);
        assertContains("history: onComplete",response);
    }

    protected void doSuspendWaitResume() throws Exception
    {
        String response=process("suspend=200&resume=10",null);
        assertContains("RESUMED",response);
        assertNotContains("history: onTimeout",response);
        assertContains("history: onComplete",response);
    }

    protected void doSuspendResume() throws Exception
    {
        String response=process("suspend=200&resume=0",null);
        assertContains("RESUMED",response);
        assertNotContains("history: onTimeout",response);
        assertContains("history: onComplete",response);
    }

    protected void doSuspendWaitComplete() throws Exception
    {
        String response=process("suspend=200&complete=10",null);
        assertContains("COMPLETED",response);
        assertNotContains("history: onTimeout",response);
        assertContains("history: onComplete",response);
    }

    protected void doSuspendComplete() throws Exception
    {
        String response=process("suspend=200&complete=0",null);
        assertContains("COMPLETED",response);
        assertNotContains("history: onTimeout",response);
        assertContains("history: onComplete",response);
    }

    protected void doSuspendWaitResumeSuspendWaitResume() throws Exception
    {
        String response=process("suspend=1000&resume=10&suspend2=1000&resume2=10",null);
        assertEquals(2,count(response,"history: suspend"));
        assertEquals(2,count(response,"history: resume"));
        assertEquals(0,count(response,"history: onTimeout"));
        assertEquals(1,count(response,"history: onComplete"));
        assertContains("RESUMED",response);
    }
    
    protected void doSuspendWaitResumeSuspendComplete() throws Exception
    {
        String response=process("suspend=1000&resume=10&suspend2=1000&complete2=10",null);
        assertEquals(2,count(response,"history: suspend"));
        assertEquals(1,count(response,"history: resume"));
        assertEquals(0,count(response,"history: onTimeout"));
        assertEquals(1,count(response,"history: onComplete"));
        assertContains("COMPLETED",response);
    }

    protected void doSuspendWaitResumeSuspend() throws Exception
    {
        String response=process("suspend=1000&resume=10&suspend2=10",null);
        assertEquals(2,count(response,"history: suspend"));
        assertEquals(1,count(response,"history: resume"));
        assertEquals(1,count(response,"history: onTimeout"));
        assertEquals(1,count(response,"history: onComplete"));
        assertContains("TIMEOUT",response);
    }

    protected void doSuspendTimeoutSuspendResume() throws Exception
    {
        String response=process("suspend=10&suspend2=1000&resume2=10",null);
        assertEquals(2,count(response,"history: suspend"));
        assertEquals(1,count(response,"history: resume"));
        assertEquals(1,count(response,"history: onTimeout"));
        assertEquals(1,count(response,"history: onComplete"));
        assertContains("RESUMED",response);
    }

    protected void doSuspendTimeoutSuspendComplete() throws Exception
    {
        String response=process("suspend=10&suspend2=1000&complete2=10",null);
        assertEquals(2,count(response,"history: suspend"));
        assertEquals(0,count(response,"history: resume"));
        assertEquals(1,count(response,"history: onTimeout"));
        assertEquals(1,count(response,"history: onComplete"));
        assertContains("COMPLETED",response);
    }

    protected void doSuspendTimeoutSuspend() throws Exception
    {
        String response=process("suspend=10&suspend2=10",null);
        assertEquals(2,count(response,"history: suspend"));
        assertEquals(0,count(response,"history: resume"));
        assertEquals(2,count(response,"history: onTimeout"));
        assertEquals(1,count(response,"history: onComplete"));
        assertContains("TIMEOUT",response);
    }

    protected void doSuspendThrowResume() throws Exception
    {
        String response=process("suspend=200&resume=10&undispatch=true",null);
        assertContains("RESUMED",response);
        assertNotContains("history: onTimeout",response);
        assertContains("history: onComplete",response);
    }

    protected void doSuspendResumeThrow() throws Exception
    {
        String response=process("suspend=200&resume=0&undispatch=true",null);
        assertContains("RESUMED",response);
        assertNotContains("history: onTimeout",response);
        assertContains("history: onComplete",response);
    }

    protected void doSuspendThrowComplete() throws Exception
    {
        String response=process("suspend=200&complete=10&undispatch=true",null);
        assertContains("COMPLETED",response);
        assertNotContains("history: onTimeout",response);
        assertContains("history: onComplete",response);
    }

    protected void doSuspendCompleteThrow() throws Exception
    {
        String response=process("suspend=200&complete=0&undispatch=true",null);
        assertContains("COMPLETED",response);
        assertNotContains("history: onTimeout",response);
        assertContains("history: onComplete",response);
    }

    
    private int count(String responses,String substring)
    {
        int count=0;
        int i=responses.indexOf(substring);
        while (i>=0)
        {
            count++;
            i=responses.indexOf(substring,i+substring.length());
        }
        
        return count;
    }
    
    protected void assertContains(String content,String response)
    {
        assertEquals("HTTP/1.1 200 OK",response.substring(0,15));
        if (response.indexOf(content,15)<0)
        {
            System.err.println("'"+content+"' NOT IN:\n"+response+"\n--");
            assertTrue(false);
        }
    }
    
    protected void assertNotContains(String content,String response)
    {
        assertEquals("HTTP/1.1 200 OK",response.substring(0,15));
        if (response.indexOf(content,15)>=0)
        {
            System.err.println("'"+content+"' IS IN:\n"+response+"'\n--");
            assertTrue(false);
        }
    }
    
    public synchronized String process(String query,String content) throws Exception
    {
        String request = "GET /";
        
        if (query!=null)
            request+="?"+query;
        request+=" HTTP/1.1\r\n"+
        "Host: localhost\r\n"+
        "Connection: close\r\n";
        if (content!=null)
            request+="Content-Length: "+content.length()+"\r\n";
        request+="\r\n" + content;
        
        Socket socket = new Socket("localhost",_port);
        socket.getOutputStream().write(request.getBytes("UTF-8"));
        
        String response = toString(socket.getInputStream());
        return response;
    }
    
    
    protected abstract String toString(InputStream in) throws IOException;
    
    
    private static class SuspendServlet extends HttpServlet
    {
        private Timer _timer=new Timer();
        
        public SuspendServlet()
        {}
        
        /* ------------------------------------------------------------ */
        protected void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
        {
            final Continuation continuation = ContinuationSupport.getContinuation(request);

            response.addHeader("history",continuation.getClass().toString());
            
            int read_before=0;
            long sleep_for=-1;
            long suspend_for=-1;
            long suspend2_for=-1;
            long resume_after=-1;
            long resume2_after=-1;
            long complete_after=-1;
            long complete2_after=-1;
            boolean undispatch=false;
            
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
            if (request.getParameter("resume2")!=null)
                resume2_after=Integer.parseInt(request.getParameter("resume2"));
            if (request.getParameter("complete")!=null)
                complete_after=Integer.parseInt(request.getParameter("complete"));
            if (request.getParameter("complete2")!=null)
                complete2_after=Integer.parseInt(request.getParameter("complete2"));
            if (request.getParameter("undispatch")!=null)
                undispatch=Boolean.parseBoolean(request.getParameter("undispatch"));
            
            if (continuation.isInitial())
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
                        continuation.setTimeout(suspend_for);
                    continuation.addContinuationListener(__listener);
                    ((HttpServletResponse)response).addHeader("history","suspend");
                    continuation.suspend(response);
                    
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
                                    continuation.complete();
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
                        continuation.complete();
                    }
                    else if (resume_after>0)
                    {
                        TimerTask resume = new TimerTask()
                        {
                            @Override
                            public void run()
                            {
                                ((HttpServletResponse)continuation.getServletResponse()).addHeader("history","resume");
                                continuation.resume();
                            }
                        };
                        synchronized (_timer)
                        {
                            _timer.schedule(resume,resume_after);
                        }
                    }
                    else if (resume_after==0)
                    {
                        ((HttpServletResponse)continuation.getServletResponse()).addHeader("history","resume");
                        continuation.resume();
                    }
                    
                    if (undispatch)
                        continuation.undispatch();
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
            else if (suspend2_for>=0 && request.getAttribute("2nd")==null)
            {
                request.setAttribute("2nd","cycle");

                if (suspend2_for>0)
                    continuation.setTimeout(suspend2_for);
                // continuation.addContinuationListener(__listener);
                ((HttpServletResponse)response).addHeader("history","suspend");
                continuation.suspend(response);

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
                                continuation.complete();
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
                    continuation.complete();
                }
                else if (resume2_after>0)
                {
                    TimerTask resume = new TimerTask()
                    {
                        @Override
                        public void run()
                        {
                            ((HttpServletResponse)response).addHeader("history","resume");
                            continuation.resume();
                        }
                    };
                    synchronized (_timer)
                    {
                        _timer.schedule(resume,resume2_after);
                    }
                }
                else if (resume2_after==0)
                {
                    ((HttpServletResponse)response).addHeader("history","resume");
                    continuation.resume();
                }
                if (undispatch)
                    continuation.undispatch();
                return;
            }
            else if (continuation.isExpired())
            {
                response.setStatus(200);
                response.getOutputStream().println("TIMEOUT\n");
            }
            else if (continuation.isResumed())
            {
                response.setStatus(200);
                response.getOutputStream().println("RESUMED\n");
            }
            else 
            {
                response.setStatus(200);
                response.getOutputStream().println("unknown???\n");
            }
        }
    }
    
    
    private static ContinuationListener __listener = new ContinuationListener()
    {
        public void onComplete(Continuation continuation)
        {
            ((HttpServletResponse)continuation.getServletResponse()).addHeader("history","onComplete");
        }

        public void onTimeout(Continuation continuation)
        {
            ((HttpServletResponse)continuation.getServletResponse()).addHeader("history","onTimeout");
            continuation.resume();
        }
        
    };
}
