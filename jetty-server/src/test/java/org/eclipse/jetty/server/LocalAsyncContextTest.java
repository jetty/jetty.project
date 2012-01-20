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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;



import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.server.session.SessionHandler;
import org.junit.After;
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
        _handler.setRead(0);
        _handler.setSuspendFor(1000);
        _handler.setResumeAfter(-1);
        _handler.setCompleteAfter(-1);
        check("TIMEOUT",process(null));

        _handler.setSuspendFor(10000);

        _handler.setResumeAfter(0);
        _handler.setCompleteAfter(-1);
        check("RESUMED",process(null));

        _handler.setResumeAfter(100);
        _handler.setCompleteAfter(-1);
        check("RESUMED",process(null));

        _handler.setResumeAfter(-1);
        _handler.setCompleteAfter(0);
        check("COMPLETED",process(null));

        _handler.setResumeAfter(-1);
        _handler.setCompleteAfter(200);
        check("COMPLETED",process(null));

        _handler.setRead(-1);

        _handler.setResumeAfter(0);
        _handler.setCompleteAfter(-1);
        check("RESUMED",process("wibble"));

        _handler.setResumeAfter(100);
        _handler.setCompleteAfter(-1);
        check("RESUMED",process("wibble"));

        _handler.setResumeAfter(-1);
        _handler.setCompleteAfter(0);
        check("COMPLETED",process("wibble"));

        _handler.setResumeAfter(-1);
        _handler.setCompleteAfter(100);
        check("COMPLETED",process("wibble"));

        _handler.setRead(6);

        _handler.setResumeAfter(0);
        _handler.setCompleteAfter(-1);
        check("RESUMED",process("wibble"));

        _handler.setResumeAfter(100);
        _handler.setCompleteAfter(-1);
        check("RESUMED",process("wibble"));

        _handler.setResumeAfter(-1);
        _handler.setCompleteAfter(0);
        check("COMPLETED",process("wibble"));

        _handler.setResumeAfter(-1);
        _handler.setCompleteAfter(100);
        check("COMPLETED",process("wibble"));
    }

    protected void check(String content,String response)
    {
        assertEquals("HTTP/1.1 200 OK",response.substring(0,15));
        assertTrue(response.contains(content));
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

        
    static ContinuationListener __asyncListener = new ContinuationListener()
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
