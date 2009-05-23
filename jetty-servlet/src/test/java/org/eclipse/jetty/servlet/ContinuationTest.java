package org.eclipse.jetty.servlet;


import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.Timer;
import java.util.TimerTask;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationFilter;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.IO;

import junit.framework.TestCase;


public class ContinuationTest extends TestCase
{
    protected Server _server = new Server();
    protected ServletHandler _servletHandler;
    protected SuspendServlet _servlet;
    protected SelectChannelConnector _connector;
    FilterHolder _filter;

    protected void setUp() throws Exception
    {
        _connector = new SelectChannelConnector();
        _server.setConnectors(new Connector[]{ _connector });
        ServletContextHandler servletContext = new ServletContextHandler(ServletContextHandler.NO_SECURITY|ServletContextHandler.NO_SESSIONS);
        _server.setHandler(servletContext);
        _servletHandler=servletContext.getServletHandler();
        _servlet=new SuspendServlet();
        ServletHolder holder=new ServletHolder(_servlet);
        _servletHandler.addServletWithMapping(holder,"/");
        _filter=_servletHandler.addFilterWithMapping(ContinuationFilter.class,"/*",0);
    }

    protected void tearDown() throws Exception
    {
        _server.stop();
    }

    public void testNotFaux() throws Exception
    {
        _filter.setInitParameter("debug","true");
        _filter.setInitParameter("faux","true");
        _server.start();
        
        doit("AsyncRequest");
    }

    public void testNotJetty6() throws Exception
    {
        _filter.setInitParameter("debug","true");
        _filter.setInitParameter("faux","false");
        _server.start();
        
        doit("AsyncRequest");
    }
    
    private void doit(String type) throws Exception
    {
        String response;
        
        response=process(null,null);
        assertContains(type,response);
        assertContains("NORMAL",response);
        assertNotContains("history: onTimeout",response);
        assertNotContains("history: onComplete",response);

        response=process("sleep=200",null);
        assertContains("SLEPT",response);
        assertNotContains("history: onTimeout",response);
        assertNotContains("history: onComplete",response);
        
        response=process("suspend=200",null);
        assertContains("TIMEOUT",response);
        assertContains("history: onTimeout",response);
        assertContains("history: onComplete",response);
        
        response=process("suspend=200&resume=100",null);
        assertContains("RESUMED",response);
        assertNotContains("history: onTimeout",response);
        assertContains("history: onComplete",response);
        
        response=process("suspend=200&resume=0",null);
        assertContains("RESUMED",response);
        assertNotContains("history: onTimeout",response);
        assertContains("history: onComplete",response);
        
        response=process("suspend=200&complete=100",null);
        assertContains("COMPLETED",response);
        assertNotContains("history: onTimeout",response);
        assertContains("history: onComplete",response);
        
        response=process("suspend=200&complete=0",null);
        assertContains("COMPLETED",response);
        assertNotContains("history: onTimeout",response);
        assertContains("history: onComplete",response);
    }

    protected void assertContains(String content,String response)
    {
        assertEquals("HTTP/1.1 200 OK",response.substring(0,15));
        if (response.indexOf(content,15)<0)
        {
            System.err.println(content+" NOT IN '"+response+"'");
            assertTrue(false);
        }
    }
    
    protected void assertNotContains(String content,String response)
    {
        assertEquals("HTTP/1.1 200 OK",response.substring(0,15));
        if (response.indexOf(content,15)>=0)
        {
            System.err.println(content+" IS IN '"+response+"'");
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
        
        Socket socket = new Socket("localhost",_connector.getLocalPort());
        socket.getOutputStream().write(request.getBytes("UTF-8"));
        
        String response = IO.toString(socket.getInputStream());
        return response;
    }
    
    private static class SuspendServlet extends HttpServlet
    {
        private Timer _timer=new Timer();
        
        public SuspendServlet()
        {}
        
        /* ------------------------------------------------------------ */
        protected void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
        {
            final Continuation continuation = ContinuationSupport.getContinuation(request,response);

            response.addHeader("history",continuation.getClass().toString());
            
            int read_before=0;
            long sleep_for=-1;
            long suspend_for=-1;
            long resume_after=-1;
            long complete_after=-1;
            
            final String uri=request.getRequestURI();
            
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
                    continuation.suspend();
                    
                    if (complete_after>0)
                    {
                        TimerTask complete = new TimerTask()
                        {
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
                            public void run()
                            {
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
                        continuation.resume();
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
