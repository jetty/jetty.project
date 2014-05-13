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

package org.eclipse.jetty.websocket.client;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.AdvancedRunner;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.io.FutureWriteCallback;
import org.eclipse.jetty.websocket.common.test.BlockheadServer;
import org.eclipse.jetty.websocket.common.test.BlockheadServer.ServerConnection;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@RunWith(AdvancedRunner.class)
public class WebSocketClientTest
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

    @Test(expected = IllegalArgumentException.class)
    public void testAddExtension_NotInstalled() throws Exception
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
            request.addExtensions("x-bad");

            // Should trigger failure on bad extension
            client.connect(cliSock,wsUri,request);
        }
        finally
        {
            client.stop();
        }
    }

    @Test
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

            final ServerConnection srvSock = server.accept();
            srvSock.upgrade();

            Session sess = future.get(500,TimeUnit.MILLISECONDS);
            Assert.assertThat("Session",sess,notNullValue());
            Assert.assertThat("Session.open",sess.isOpen(),is(true));
            Assert.assertThat("Session.upgradeRequest",sess.getUpgradeRequest(),notNullValue());
            Assert.assertThat("Session.upgradeResponse",sess.getUpgradeResponse(),notNullValue());

            cliSock.assertWasOpened();
            cliSock.assertNotClosed();

            Assert.assertThat("client.connectionManager.sessions.size",client.getConnectionManager().getSessions().size(),is(1));

            RemoteEndpoint remote = cliSock.getSession().getRemote();
            remote.sendStringByFuture("Hello World!");
            if (remote.getBatchMode() == BatchMode.ON)
                remote.flush();
            srvSock.echoMessage(1,500,TimeUnit.MILLISECONDS);
            // wait for response from server
            cliSock.waitForMessage(500,TimeUnit.MILLISECONDS);

            cliSock.assertMessage("Hello World!");
        }
        finally
        {
            client.stop();
        }
    }

    @Test
    public void testBasicEcho_UsingCallback() throws Exception
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

            final ServerConnection srvSock = server.accept();
            srvSock.upgrade();

            Session sess = future.get(500,TimeUnit.MILLISECONDS);
            Assert.assertThat("Session",sess,notNullValue());
            Assert.assertThat("Session.open",sess.isOpen(),is(true));
            Assert.assertThat("Session.upgradeRequest",sess.getUpgradeRequest(),notNullValue());
            Assert.assertThat("Session.upgradeResponse",sess.getUpgradeResponse(),notNullValue());

            cliSock.assertWasOpened();
            cliSock.assertNotClosed();

            Assert.assertThat("client.connectionManager.sessions.size",client.getConnectionManager().getSessions().size(),is(1));

            FutureWriteCallback callback = new FutureWriteCallback();

            cliSock.getSession().getRemote().sendString("Hello World!",callback);
            callback.get(1,TimeUnit.SECONDS);
        }
        finally
        {
            client.stop();
        }
    }

    @Test
    public void testBasicEcho_FromServer() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        client.start();
        try
        {
            JettyTrackingSocket wsocket = new JettyTrackingSocket();
            Future<Session> future = client.connect(wsocket,server.getWsUri());

            // Server
            final ServerConnection srvSock = server.accept();
            srvSock.upgrade();

            // Validate connect
            Session sess = future.get(500,TimeUnit.MILLISECONDS);
            Assert.assertThat("Session",sess,notNullValue());
            Assert.assertThat("Session.open",sess.isOpen(),is(true));
            Assert.assertThat("Session.upgradeRequest",sess.getUpgradeRequest(),notNullValue());
            Assert.assertThat("Session.upgradeResponse",sess.getUpgradeResponse(),notNullValue());

            // Have server send initial message
            srvSock.write(new TextFrame().setPayload("Hello World"));

            // Verify connect
            future.get(500,TimeUnit.MILLISECONDS);
            wsocket.assertWasOpened();
            wsocket.awaitMessage(1,TimeUnit.SECONDS,2);

            wsocket.assertMessage("Hello World");
        }
        finally
        {
            client.stop();
        }
    }

    @Test
    public void testLocalRemoteAddress() throws Exception
    {
        WebSocketClient fact = new WebSocketClient();
        fact.start();
        try
        {
            JettyTrackingSocket wsocket = new JettyTrackingSocket();

            URI wsUri = server.getWsUri();
            Future<Session> future = fact.connect(wsocket,wsUri);

            ServerConnection ssocket = server.accept();
            ssocket.upgrade();

            future.get(500,TimeUnit.MILLISECONDS);

            Assert.assertTrue(wsocket.openLatch.await(1,TimeUnit.SECONDS));

            InetSocketAddress local = wsocket.getSession().getLocalAddress();
            InetSocketAddress remote = wsocket.getSession().getRemoteAddress();

            Assert.assertThat("Local Socket Address",local,notNullValue());
            Assert.assertThat("Remote Socket Address",remote,notNullValue());

            // Hard to validate (in a portable unit test) the local address that was used/bound in the low level Jetty Endpoint
            Assert.assertThat("Local Socket Address / Host",local.getAddress().getHostAddress(),notNullValue());
            Assert.assertThat("Local Socket Address / Port",local.getPort(),greaterThan(0));

            Assert.assertThat("Remote Socket Address / Host",remote.getAddress().getHostAddress(),is(wsUri.getHost()));
            Assert.assertThat("Remote Socket Address / Port",remote.getPort(),greaterThan(0));
        }
        finally
        {
            fact.stop();
        }
    }

    @Test
    public void testMessageBiggerThanBufferSize() throws Exception
    {
        WebSocketClient factSmall = new WebSocketClient();
        factSmall.start();
        try
        {
            int bufferSize = 512;

            JettyTrackingSocket wsocket = new JettyTrackingSocket();

            URI wsUri = server.getWsUri();
            Future<Session> future = factSmall.connect(wsocket,wsUri);

            ServerConnection ssocket = server.accept();
            ssocket.upgrade();

            future.get(500,TimeUnit.MILLISECONDS);

            Assert.assertTrue(wsocket.openLatch.await(1,TimeUnit.SECONDS));

            int length = bufferSize + (bufferSize / 2); // 1.5 times buffer size
            ssocket.write(0x80 | 0x01); // FIN + TEXT
            ssocket.write(0x7E); // No MASK and 2 bytes length
            ssocket.write(length >> 8); // first length byte
            ssocket.write(length & 0xFF); // second length byte
            for (int i = 0; i < length; ++i)
            {
                ssocket.write('x');
            }
            ssocket.flush();

            Assert.assertTrue(wsocket.dataLatch.await(1000,TimeUnit.SECONDS));
        }
        finally
        {
            factSmall.stop();
        }
    }

    @Test
    public void testMaxMessageSize() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        client.start();
        try
        {
            MaxMessageSocket wsocket = new MaxMessageSocket();

            URI wsUri = server.getWsUri();
            Future<Session> future = client.connect(wsocket,wsUri);

            ServerConnection ssocket = server.accept();
            ssocket.upgrade();

            wsocket.awaitConnect(1,TimeUnit.SECONDS);

            Session sess = future.get(500,TimeUnit.MILLISECONDS);
            Assert.assertThat("Session",sess,notNullValue());
            Assert.assertThat("Session.open",sess.isOpen(),is(true));

            // Create string that is larger than default size of 64k
            // but smaller than maxMessageSize of 100k
            byte buf[] = new byte[80 * 1024];
            Arrays.fill(buf,(byte)'x');
            String msg = StringUtil.toUTF8String(buf,0,buf.length);

            wsocket.getSession().getRemote().sendStringByFuture(msg);
            ssocket.echoMessage(1,2,TimeUnit.SECONDS);
            // wait for response from server
            wsocket.waitForMessage(1,TimeUnit.SECONDS);

            wsocket.assertMessage(msg);

            Assert.assertTrue(wsocket.dataLatch.await(2,TimeUnit.SECONDS));
        }
        finally
        {
            client.stop();
        }
    }

    @Test
    public void testParameterMap() throws Exception
    {
        WebSocketClient fact = new WebSocketClient();
        fact.start();
        try
        {
            JettyTrackingSocket wsocket = new JettyTrackingSocket();

            URI wsUri = server.getWsUri().resolve("/test?snack=cashews&amount=handful&brand=off");
            Future<Session> future = fact.connect(wsocket,wsUri);

            ServerConnection ssocket = server.accept();
            ssocket.upgrade();

            future.get(500,TimeUnit.MILLISECONDS);

            Assert.assertTrue(wsocket.openLatch.await(1,TimeUnit.SECONDS));

            Session session = wsocket.getSession();
            UpgradeRequest req = session.getUpgradeRequest();
            Assert.assertThat("Upgrade Request",req,notNullValue());

            Map<String, List<String>> parameterMap = req.getParameterMap();
            Assert.assertThat("Parameter Map",parameterMap,notNullValue());

            Assert.assertThat("Parameter[snack]",parameterMap.get("snack"),is(Arrays.asList(new String[]
            { "cashews" })));
            Assert.assertThat("Parameter[amount]",parameterMap.get("amount"),is(Arrays.asList(new String[]
            { "handful" })));
            Assert.assertThat("Parameter[brand]",parameterMap.get("brand"),is(Arrays.asList(new String[]
            { "off" })));

            Assert.assertThat("Parameter[cost]",parameterMap.get("cost"),nullValue());
        }
        finally
        {
            fact.stop();
        }
    }
}
