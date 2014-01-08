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

package org.eclipse.jetty.server;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.session.SessionHandler;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class LocalAsyncContextTest
{
    protected Server _server = new Server();
    protected SuspendHandler _handler = new SuspendHandler();
    protected Connector _connector;

    @Before
    public void init() throws Exception
    {
        _connector = initConnector();
        _server.setConnectors(new Connector[]{ _connector });

        SessionHandler session = new SessionHandler();
        session.setHandler(_handler);

        _server.setHandler(session);
        _server.start();

        __completed.set(0);
        __completed1.set(0);
    }

    protected Connector initConnector()
    {
        return new LocalConnector(_server);
    }

    @After
    public void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testSuspendTimeout() throws Exception
    {
        String response;
        _handler.setRead(0);
        _handler.setSuspendFor(1000);
        _handler.setResumeAfter(-1);
        _handler.setCompleteAfter(-1);
        response=process(null);
        check(response,"TIMEOUT");
        assertEquals(1,__completed.get());
        assertEquals(1,__completed1.get());
    }

    @Test
    public void testSuspendResume0() throws Exception
    {
        String response;
        _handler.setRead(0);
        _handler.setSuspendFor(10000);
        _handler.setResumeAfter(0);
        _handler.setCompleteAfter(-1);
        response=process(null);
        check(response,"STARTASYNC","DISPATCHED");
    }

    @Test
    public void testSuspendResume100() throws Exception
    {
        String response;
        _handler.setRead(0);
        _handler.setSuspendFor(10000);
        _handler.setResumeAfter(100);
        _handler.setCompleteAfter(-1);
        response=process(null);
        check(response,"STARTASYNC","DISPATCHED");
    }

    @Test
    public void testSuspendComplete0() throws Exception
    {
        String response;
        _handler.setRead(0);
        _handler.setSuspendFor(10000);
        _handler.setResumeAfter(-1);
        _handler.setCompleteAfter(0);
        response=process(null);
        check(response,"STARTASYNC","COMPLETED");
    }

    @Test
    public void testSuspendComplete200() throws Exception
    {
        String response;
        _handler.setRead(0);
        _handler.setSuspendFor(10000);
        _handler.setResumeAfter(-1);
        _handler.setCompleteAfter(200);
        response=process(null);
        check(response,"STARTASYNC","COMPLETED");

    }

    @Test
    public void testSuspendReadResume0() throws Exception
    {
        String response;
        _handler.setSuspendFor(10000);
        _handler.setRead(-1);
        _handler.setResumeAfter(0);
        _handler.setCompleteAfter(-1);
        response=process("wibble");
        check(response,"STARTASYNC","DISPATCHED");
    }

    @Test
    public void testSuspendReadResume100() throws Exception
    {
        String response;
        _handler.setSuspendFor(10000);
        _handler.setRead(-1);
        _handler.setResumeAfter(100);
        _handler.setCompleteAfter(-1);
        response=process("wibble");
        check(response,"DISPATCHED");

    }

    @Test
    public void testSuspendOther() throws Exception
    {
        String response;
        _handler.setSuspendFor(10000);
        _handler.setRead(-1);
        _handler.setResumeAfter(-1);
        _handler.setCompleteAfter(0);
        response=process("wibble");
        check(response,"COMPLETED");

        _handler.setResumeAfter(-1);
        _handler.setCompleteAfter(100);
        response=process("wibble");
        check(response,"COMPLETED");

        _handler.setRead(6);

        _handler.setResumeAfter(0);
        _handler.setCompleteAfter(-1);
        response=process("wibble");
        check(response,"DISPATCHED");

        _handler.setResumeAfter(100);
        _handler.setCompleteAfter(-1);
        response=process("wibble");
        check(response,"DISPATCHED");

        _handler.setResumeAfter(-1);
        _handler.setCompleteAfter(0);
        response=process("wibble");
        check(response,"COMPLETED");

        _handler.setResumeAfter(-1);
        _handler.setCompleteAfter(100);
        response=process("wibble");
        check(response,"COMPLETED");
    }

    @Test
    public void testTwoCycles() throws Exception
    {
        String response;

        __completed.set(0);
        __completed1.set(0);

        _handler.setRead(0);
        _handler.setSuspendFor(1000);
        _handler.setResumeAfter(100);
        _handler.setCompleteAfter(-1);
        _handler.setSuspendFor2(1000);
        _handler.setResumeAfter2(200);
        _handler.setCompleteAfter2(-1);
        response=process(null);
        check(response,"STARTASYNC","DISPATCHED","startasync","STARTASYNC","DISPATCHED");
        assertEquals(1,__completed.get());
        assertEquals(0,__completed1.get());

    }

    protected void check(String response,String... content)
    {
        Assert.assertThat(response,Matchers.startsWith("HTTP/1.1 200 OK"));
        int i=0;
        for (String m:content)
        {
            Assert.assertThat(response,Matchers.containsString(m));
            i=response.indexOf(m,i);
            i+=m.length();
        }
    }

    private synchronized String process(String content) throws Exception
    {
        String request = "GET / HTTP/1.1\r\n" +
        "Host: localhost\r\n"+
        "Connection: close\r\n";

        if (content==null)
            request+="\r\n";
        else
            request+="Content-Length: "+content.length()+"\r\n" +"\r\n" + content;

        String response=getResponse(request);
        return response;
    }

    protected String getResponse(String request) throws Exception
    {
        LocalConnector connector=(LocalConnector)_connector;
        LocalConnector.LocalEndPoint endp = connector.executeRequest(request);
        endp.waitUntilClosed();
        return endp.takeOutputString();
    }

    private static class SuspendHandler extends HandlerWrapper
    {
        private int _read;
        private long _suspendFor=-1;
        private long _resumeAfter=-1;
        private long _completeAfter=-1;
        private long _suspendFor2=-1;
        private long _resumeAfter2=-1;
        private long _completeAfter2=-1;

        public SuspendHandler()
        {
        }

        public int getRead()
        {
            return _read;
        }

        public void setRead(int read)
        {
            _read = read;
        }

        public long getSuspendFor()
        {
            return _suspendFor;
        }

        public void setSuspendFor(long suspendFor)
        {
            _suspendFor = suspendFor;
        }

        public long getResumeAfter()
        {
            return _resumeAfter;
        }

        public void setResumeAfter(long resumeAfter)
        {
            _resumeAfter = resumeAfter;
        }

        public long getCompleteAfter()
        {
            return _completeAfter;
        }

        public void setCompleteAfter(long completeAfter)
        {
            _completeAfter = completeAfter;
        }



        /* ------------------------------------------------------------ */
        /** Get the suspendFor2.
         * @return the suspendFor2
         */
        public long getSuspendFor2()
        {
            return _suspendFor2;
        }


        /* ------------------------------------------------------------ */
        /** Set the suspendFor2.
         * @param suspendFor2 the suspendFor2 to set
         */
        public void setSuspendFor2(long suspendFor2)
        {
            _suspendFor2 = suspendFor2;
        }


        /* ------------------------------------------------------------ */
        /** Get the resumeAfter2.
         * @return the resumeAfter2
         */
        public long getResumeAfter2()
        {
            return _resumeAfter2;
        }


        /* ------------------------------------------------------------ */
        /** Set the resumeAfter2.
         * @param resumeAfter2 the resumeAfter2 to set
         */
        public void setResumeAfter2(long resumeAfter2)
        {
            _resumeAfter2 = resumeAfter2;
        }


        /* ------------------------------------------------------------ */
        /** Get the completeAfter2.
         * @return the completeAfter2
         */
        public long getCompleteAfter2()
        {
            return _completeAfter2;
        }


        /* ------------------------------------------------------------ */
        /** Set the completeAfter2.
         * @param completeAfter2 the completeAfter2 to set
         */
        public void setCompleteAfter2(long completeAfter2)
        {
            _completeAfter2 = completeAfter2;
        }


        /* ------------------------------------------------------------ */
        @Override
        public void handle(String target, final Request baseRequest, final HttpServletRequest request, final HttpServletResponse response) throws IOException, ServletException
        {
            try
            {
                if (DispatcherType.REQUEST.equals(baseRequest.getDispatcherType()))
                {
                    if (_read>0)
                    {
                        byte[] buf=new byte[_read];
                        request.getInputStream().read(buf);
                    }
                    else if (_read<0)
                    {
                        InputStream in = request.getInputStream();
                        int b=in.read();
                        while(b!=-1)
                            b=in.read();
                    }

                    final AsyncContext asyncContext = baseRequest.startAsync();
                    response.getOutputStream().println("STARTASYNC");
                    asyncContext.addListener(__asyncListener);
                    asyncContext.addListener(__asyncListener1);
                    if (_suspendFor>0)
                        asyncContext.setTimeout(_suspendFor);


                    if (_completeAfter>0)
                    {
                        new Thread() {
                            @Override
                            public void run()
                            {
                                try
                                {
                                    Thread.sleep(_completeAfter);
                                    response.getOutputStream().println("COMPLETED");
                                    response.setStatus(200);
                                    baseRequest.setHandled(true);
                                    asyncContext.complete();
                                }
                                catch(Exception e)
                                {
                                    e.printStackTrace();
                                }
                            }
                        }.start();
                    }
                    else if (_completeAfter==0)
                    {
                        response.getOutputStream().println("COMPLETED");
                        response.setStatus(200);
                        baseRequest.setHandled(true);
                        asyncContext.complete();
                    }

                    if (_resumeAfter>0)
                    {
                        new Thread() {
                            @Override
                            public void run()
                            {
                                try
                                {
                                    Thread.sleep(_resumeAfter);
                                    if(((HttpServletRequest)asyncContext.getRequest()).getSession(true).getId()!=null)
                                        asyncContext.dispatch();
                                }
                                catch(Exception e)
                                {
                                    e.printStackTrace();
                                }
                            }
                        }.start();
                    }
                    else if (_resumeAfter==0)
                    {
                        asyncContext.dispatch();
                    }
                }
                else
                {
                    if (request.getAttribute("TIMEOUT")!=null)
                    {
                        response.getOutputStream().println("TIMEOUT");
                    }
                    else
                    {
                        response.getOutputStream().println("DISPATCHED");
                    }

                    if (_suspendFor2>=0)
                    {
                        final AsyncContext asyncContext = baseRequest.startAsync();
                        response.getOutputStream().println("STARTASYNC2");
                        if (_suspendFor2>0)
                            asyncContext.setTimeout(_suspendFor2);
                        _suspendFor2=-1;

                        if (_completeAfter2>0)
                        {
                            new Thread() {
                                @Override
                                public void run()
                                {
                                    try
                                    {
                                        Thread.sleep(_completeAfter2);
                                        response.getOutputStream().println("COMPLETED2");
                                        response.setStatus(200);
                                        baseRequest.setHandled(true);
                                        asyncContext.complete();
                                    }
                                    catch(Exception e)
                                    {
                                        e.printStackTrace();
                                    }
                                }
                            }.start();
                        }
                        else if (_completeAfter2==0)
                        {
                            response.getOutputStream().println("COMPLETED2");
                            response.setStatus(200);
                            baseRequest.setHandled(true);
                            asyncContext.complete();
                        }

                        if (_resumeAfter2>0)
                        {
                            new Thread() {
                                @Override
                                public void run()
                                {
                                    try
                                    {
                                        Thread.sleep(_resumeAfter2);
                                        asyncContext.dispatch();
                                    }
                                    catch(Exception e)
                                    {
                                        e.printStackTrace();
                                    }
                                }
                            }.start();
                        }
                        else if (_resumeAfter2==0)
                        {
                            asyncContext.dispatch();
                        }
                    }
                    else
                    {
                        response.setStatus(200);
                        baseRequest.setHandled(true);
                    }
                }
            }
            finally
            {
            }
        }
    }

    static AtomicInteger __completed = new AtomicInteger();
    static AtomicInteger __completed1 = new AtomicInteger();

    private static AsyncListener __asyncListener = new AsyncListener()
    {

        @Override
        public void onComplete(AsyncEvent event) throws IOException
        {
            __completed.incrementAndGet();
        }

        @Override
        public void onError(AsyncEvent event) throws IOException
        {
            __completed.incrementAndGet();
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException
        {
            event.getSuppliedResponse().getOutputStream().println("startasync");
            event.getAsyncContext().addListener(this);
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
            event.getSuppliedRequest().setAttribute("TIMEOUT",Boolean.TRUE);
            event.getAsyncContext().dispatch();
        }
    };

    private static AsyncListener __asyncListener1 = new AsyncListener()
    {
        @Override
        public void onComplete(AsyncEvent event) throws IOException
        {
            __completed1.incrementAndGet();
        }

        @Override
        public void onError(AsyncEvent event) throws IOException
        {
        }
        @Override
        public void onStartAsync(AsyncEvent event) throws IOException
        {
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
        }

    };
}
