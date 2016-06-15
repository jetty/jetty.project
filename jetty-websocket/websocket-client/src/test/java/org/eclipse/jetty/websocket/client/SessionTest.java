//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client;

import static org.hamcrest.Matchers.*;

import java.net.URI;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.test.BlockheadServer;
import org.eclipse.jetty.websocket.common.test.IBlockheadServerConnection;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class SessionTest
{
    private BlockheadServer server;

    @Before
    public void startServer() throws Exception
    {
        server = new BlockheadServer();
        server.start();
    }

    @After
    public void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Test
    @Ignore // TODO fix frequent failure
    public void testBasicEcho_FromClient() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        client.start();
        try
        {
            JettyTrackingSocket cliSock = new JettyTrackingSocket();

            client.getPolicy().setIdleTimeout(10000);

            URI wsUri = server.getWsUri();
            ClientUpgradeRequest request = new ClientUpgradeRequest();
            request.setSubProtocols("echo");
            Future<Session> future = client.connect(cliSock,wsUri,request);

            final IBlockheadServerConnection srvSock = server.accept();
            srvSock.upgrade();

            Session sess = future.get(30000,TimeUnit.MILLISECONDS);
            Assert.assertThat("Session",sess,notNullValue());
            Assert.assertThat("Session.open",sess.isOpen(),is(true));
            Assert.assertThat("Session.upgradeRequest",sess.getUpgradeRequest(),notNullValue());
            Assert.assertThat("Session.upgradeResponse",sess.getUpgradeResponse(),notNullValue());

            cliSock.assertWasOpened();
            cliSock.assertNotClosed();

            Collection<WebSocketSession> sessions = client.getBeans(WebSocketSession.class);
            Assert.assertThat("client.connectionManager.sessions.size",sessions.size(),is(1));

            RemoteEndpoint remote = cliSock.getSession().getRemote();
            remote.sendStringByFuture("Hello World!");
            if (remote.getBatchMode() == BatchMode.ON)
            {
                remote.flush();
            }
            srvSock.echoMessage(1,30000,TimeUnit.MILLISECONDS);
            // wait for response from server
            cliSock.waitForMessage(30000,TimeUnit.MILLISECONDS);
            
            Set<WebSocketSession> open = client.getOpenSessions();
            Assert.assertThat("(Before Close) Open Sessions.size", open.size(), is(1));

            cliSock.assertMessage("Hello World!");
            cliSock.close();
            srvSock.close();
            
            cliSock.waitForClose(30000,TimeUnit.MILLISECONDS);
            open = client.getOpenSessions();
            // TODO this sometimes fails!
            Assert.assertThat("(After Close) Open Sessions.size", open.size(), is(0));
        }
        finally
        {
            client.stop();
        }
    }
}
