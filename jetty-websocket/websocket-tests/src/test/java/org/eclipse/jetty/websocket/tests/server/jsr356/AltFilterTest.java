//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests.server.jsr356;

import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.core.CloseInfo;
import org.eclipse.jetty.websocket.core.WebSocketFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.tests.LocalFuzzer;
import org.eclipse.jetty.websocket.tests.WSServer;
import org.eclipse.jetty.websocket.tests.server.jsr356.sockets.BasicEchoSocket;
import org.junit.Rule;
import org.junit.Test;

/**
 * Testing the use of an alternate {@link org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter}
 * defined in the WEB-INF/web.xml
 */
public class AltFilterTest
{
    @Rule
    public TestingDir testdir = new TestingDir();

    @Test
    public void testEcho() throws Exception
    {
        WSServer wsb = new WSServer(testdir,"app");
        wsb.copyWebInf("alt-filter-web.xml");
        // the endpoint (extends javax.websocket.Endpoint)
        wsb.copyClass(BasicEchoSocket.class);

        try
        {
            wsb.start();
            
            WebAppContext webapp = wsb.createWebAppContext();
            wsb.deployWebapp(webapp);
            
            FilterHolder filterWebXml = webapp.getServletHandler().getFilter("wsuf-test");
            assertThat("Filter[wsuf-test]", filterWebXml, notNullValue());
            
            FilterHolder filterSCI = webapp.getServletHandler().getFilter("Jetty_WebSocketUpgradeFilter");
            assertThat("Filter[Jetty_WebSocketUpgradeFilter]", filterSCI, nullValue());

            List<WebSocketFrame> send = new ArrayList<>();
            send.add(new TextFrame().setPayload("Hello Echo"));
            send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
            List<WebSocketFrame> expect = new ArrayList<>();
            expect.add(new TextFrame().setPayload("Hello Echo"));
            expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
            
            try(LocalFuzzer session = wsb.newLocalFuzzer("/app/echo;jsession=xyz"))
            {
                session.sendFrames(send);
                session.expect(expect);
            }
        }
        finally
        {
            wsb.stop();
        }
    }
}
