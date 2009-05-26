// ========================================================================
// Copyright 2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at 
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//========================================================================

package org.eclipse.jetty.servlets;

import java.io.IOException;
import java.net.Socket;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.testing.ServletTester;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;

public class DoSFilterTest extends TestCase
{
    protected ServletTester _tester;
    protected String _host;
    protected int _port;
    
    protected int _maxRequestMs = 200;
    protected void setUp() throws Exception 
    {
        _tester = new ServletTester();
        HttpURI uri=new HttpURI(_tester.createChannelConnector(true));
        _host=uri.getHost();
        _port=uri.getPort();
        
        _tester.setContextPath("/ctx");
        _tester.addServlet(TestServlet.class, "/*");
        
        FilterHolder dos=_tester.addFilter(DoSFilter2.class,"/dos/*",0);
        dos.setInitParameter("maxRequestsPerSec","4");
        dos.setInitParameter("delayMs","200");
        dos.setInitParameter("throttledRequests","1");
        dos.setInitParameter("waitMs","10");
        dos.setInitParameter("throttleMs","4000");
        dos.setInitParameter("remotePort", "false");
        dos.setInitParameter("insertHeaders", "true");
        
        FilterHolder quickTimeout = _tester.addFilter(DoSFilter2.class,"/timeout/*",0);
        quickTimeout.setInitParameter("maxRequestsPerSec","4");
        quickTimeout.setInitParameter("delayMs","200");
        quickTimeout.setInitParameter("throttledRequests","1");
        quickTimeout.setInitParameter("waitMs","10");
        quickTimeout.setInitParameter("throttleMs","4000");
        quickTimeout.setInitParameter("remotePort", "false");
        quickTimeout.setInitParameter("insertHeaders", "true");
        quickTimeout.setInitParameter("maxRequestMs", _maxRequestMs + "");

        _tester.start();

    }
        
    protected void tearDown() throws Exception 
    {
        _tester.stop();
    }
    
    private String doRequests(String requests, int loops, long pause0,long pause1,String request)
        throws Exception
    {
        Socket socket = new Socket(_host,_port);
        socket.setSoTimeout(30000);
        
        for (int i=loops;i-->0;)
        {
            socket.getOutputStream().write(requests.getBytes("UTF-8"));
            socket.getOutputStream().flush();
            if (i>0 && pause0>0)
                Thread.sleep(pause0);
        }
        if (pause1>0)
            Thread.sleep(pause1);
        socket.getOutputStream().write(request.getBytes("UTF-8"));
        socket.getOutputStream().flush();
        
        
        String response = "";

        if (requests.contains("/unresponsive"))
        {
            // don't read in anything, forcing the request to time out
            Thread.sleep(_maxRequestMs * 2);
            response = IO.toString(socket.getInputStream(),"UTF-8");
        }
        else
        {
            response = IO.toString(socket.getInputStream(),"UTF-8");
        }
        socket.close();
        return response;
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
    
    public void testEvenLowRateIP()
        throws Exception
    {
        String request="GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\n\r\n";
        String last="GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
        String responses = doRequests(request,11,300,300,last);   
        assertEquals(12,count(responses,"HTTP/1.1 200 OK"));
        assertEquals(0,count(responses,"DoSFilter:"));
    }
    
    public void testBurstLowRateIP()
        throws Exception
    {
        String request="GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\n\r\n";
        String last="GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
        String responses = doRequests(request+request+request+request,2,1100,1100,last);   
        
        assertEquals(9,count(responses,"HTTP/1.1 200 OK"));
        assertEquals(0,count(responses,"DoSFilter:"));
    }
    
    public void testDelayedIP()
        throws Exception
    {
        String request="GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\n\r\n";
        String last="GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
        String responses = doRequests(request+request+request+request+request,2,1100,1100,last);
        
        assertEquals(11,count(responses,"HTTP/1.1 200 OK"));
        assertEquals(2,count(responses,"DoSFilter: delayed"));
    }
    
    public void testThrottledIP()
        throws Exception
    {
        Thread other = new Thread()
        {
            public void run()
            {
                try
                {
                    // Cause a delay, then sleep while holding pass
                    String request="GET /ctx/dos/sleeper HTTP/1.1\r\nHost: localhost\r\n\r\n";
                    String last="GET /ctx/dos/sleeper?sleep=2000 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
                    String responses = doRequests(request+request+request+request,1,0,0,last);
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                }
            }
        };
        other.start();
        Thread.sleep(1500);
        
        String request="GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\n\r\n";
        String last="GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
        String responses = doRequests(request+request+request+request,1,0,0,last);
        //System.out.println("responses are " + responses);
        assertEquals(5,count(responses,"HTTP/1.1 200 OK"));
        assertEquals(1,count(responses,"DoSFilter: delayed"));
        assertEquals(1,count(responses,"DoSFilter: throttled"));
        assertEquals(0,count(responses,"DoSFilter: unavailable"));
        
        other.join();
    }
    
    public void testUnavailableIP()
        throws Exception
    {
        Thread other = new Thread()
        {
            public void run()
            {
                try
                {
                    // Cause a delay, then sleep while holding pass
                    String request="GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\n\r\n";
                    String last="GET /ctx/dos/test?sleep=5000 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
                    String responses = doRequests(request+request+request+request,1,0,0,last);
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                }
            }
        };
        other.start();
        Thread.sleep(500);
        
        String request="GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\n\r\n";
        String last="GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
        String responses = doRequests(request+request+request+request,1,0,0,last);
        
        assertEquals(4,count(responses,"HTTP/1.1 200 OK"));
        assertEquals(1,count(responses,"HTTP/1.1 503"));
        assertEquals(1,count(responses,"DoSFilter: delayed"));
        assertEquals(1,count(responses,"DoSFilter: throttled"));
        assertEquals(1,count(responses,"DoSFilter: unavailable"));
        
        other.join();
    }
    
    public void testSessionTracking()
        throws Exception
    {
        // get a session, first
        String requestSession="GET /ctx/dos/test?session=true HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
        String response=doRequests("",1,0,0,requestSession);
        String sessionId=response.substring(response.indexOf("Set-Cookie: ")+12, response.indexOf(";"));

        // all other requests use this session
        String request="GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\nCookie: " + sessionId + "\r\n\r\n";
        String last="GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nCookie: " + sessionId + "\r\n\r\n";
        String responses = doRequests(request+request+request+request+request,2,1100,1100,last);

        assertEquals(11,count(responses,"HTTP/1.1 200 OK"));
        assertEquals(2,count(responses,"DoSFilter: delayed"));
    }

    public void testMultipleSessionTracking()
        throws Exception
    {
        // get some session ids, first
        String requestSession="GET /ctx/dos/test?session=true HTTP/1.1\r\nHost: localhost\r\n\r\n";
        String closeRequest="GET /ctx/dos/test?session=true HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
        String response=doRequests(requestSession+requestSession,1,0,0,closeRequest);

        String[] sessions = response.split("\r\n\r\n");

        String sessionId1=sessions[0].substring(sessions[0].indexOf("Set-Cookie: ")+12, sessions[0].indexOf(";"));
        String sessionId2=sessions[1].substring(sessions[1].indexOf("Set-Cookie: ")+12, sessions[1].indexOf(";"));

        // alternate between sessions
        String request1="GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\nCookie: " + sessionId1 + "\r\n\r\n";
        String request2="GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\nCookie: " + sessionId2 + "\r\n\r\n";
        String last="GET /ctx/dos/test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nCookie: " + sessionId2 + "\r\n\r\n";
        String responses = doRequests(request1+request2+request1+request2+request1,2,1100,1100,last);

        assertEquals(11,count(responses,"HTTP/1.1 200 OK"));
        assertEquals(0,count(responses,"DoSFilter: delayed"));

        // alternate between sessions
        responses = doRequests(request1+request2+request1+request2+request1,2,550,550,last);

        assertEquals(11,count(responses,"HTTP/1.1 200 OK"));
        int delayedRequests = count(responses,"DoSFilter: delayed"); 
        assertTrue(delayedRequests >= 2 && delayedRequests <= 3);
    }    

    public void testUnresponsiveClient()
        throws Exception
    {
        int numRequests = 1000;

        String last="GET /ctx/timeout/unresponsive?lines="+numRequests+" HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
        String responses = doRequests("",0,0,0,last);
        // was expired, and stopped before reaching the end of the requests
        int responseLines = count(responses, "Line:"); 
        assertTrue(responses.contains("DoSFilter: timeout"));
        assertTrue(responseLines > 0 && responseLines < numRequests);
    }
   
    public static class TestServlet extends HttpServlet implements Servlet
    {
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            if (request.getParameter("session")!=null)
                request.getSession(true);
            if (request.getParameter("sleep")!=null)
            {
                try
                {
                    Thread.sleep(Long.parseLong(request.getParameter("sleep")));
                }
                catch(InterruptedException e)
                {    
                }
            }
            
            if (request.getParameter("lines")!=null)
            {
                int count = Integer.parseInt(request.getParameter("lines"));
                for(int i = 0; i < count; ++i)
                {                        
                    response.getWriter().append("Line: " + i+"\n");
                    response.flushBuffer();
                    
                    try
                    {
                        Thread.sleep(10);
                    }
                    catch(InterruptedException e)
                    {
                    }

                }
            }
            
            response.setContentType("text/plain");
            
        }
    }
    
    public static class DoSFilter2 extends DoSFilter
    {
        public void closeConnection(HttpServletRequest request, HttpServletResponse response, Thread thread)
        {
            try {
                response.getWriter().append("DoSFilter: timeout");
                super.closeConnection(request,response,thread);
            }
            catch (Exception e)
            {
                Log.warn(e);
            }
        }
    }    
}
