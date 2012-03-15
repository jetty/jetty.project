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
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;

import org.eclipse.jetty.server.session.SessionHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
    }

    protected Connector initConnector()
    {
        return new LocalConnector();
    }

    @After
    public void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testSuspendResume() throws Exception
    {
        String response;
        __completed.set(0);
        __completed1.set(0);
        _handler.setRead(0);
        _handler.setSuspendFor(1000);
        _handler.setResumeAfter(-1);
        _handler.setCompleteAfter(-1);
        response=process(null);
        check(response,"TIMEOUT");
        assertEquals(1,__completed.get());
        assertEquals(1,__completed1.get());

        _handler.setSuspendFor(10000);

        _handler.setResumeAfter(0);
        _handler.setCompleteAfter(-1);
        response=process(null);
        check(response,"DISPATCHED");

        _handler.setResumeAfter(100);
        _handler.setCompleteAfter(-1);
        response=process(null);
        check(response,"DISPATCHED");

        _handler.setResumeAfter(-1);
        _handler.setCompleteAfter(0);
        response=process(null);
        check(response,"COMPLETED");

        _handler.setResumeAfter(-1);
        _handler.setCompleteAfter(200);
        response=process(null);
        check(response,"COMPLETED");

        _handler.setRead(-1);

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
        assertEquals("HTTP/1.1 200 OK",response.substring(0,15));
        int i=0;
        for (String m:content)
        {
            i=response.indexOf(m,i);
            assertTrue(i>=0);
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

        return getResponse(request);
    }

    protected String getResponse(String request) throws Exception
    {
        return ((LocalConnector)_connector).getResponses(request);
    }



    static AtomicInteger __completed = new AtomicInteger();
    static AtomicInteger __completed1 = new AtomicInteger();

    static AsyncListener __asyncListener = new AsyncListener()
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

    static AsyncListener __asyncListener1 = new AsyncListener()
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
