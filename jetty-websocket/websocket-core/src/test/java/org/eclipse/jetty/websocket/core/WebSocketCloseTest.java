//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.frames.Frame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.io.BatchMode;
import org.eclipse.jetty.websocket.core.server.Negotiation;
import org.eclipse.jetty.websocket.core.server.RFC6455Handshaker;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;


/**
 * Tests of a core server with a fake client
 *
 */
public class WebSocketCloseTest
{
    @Rule
    public TestTracker tracker = new TestTracker();
    
    private static Logger LOG = Log.getLogger(WebSocketCloseTest.class);
    private static String NON_RANDOM_KEY = new String(B64Code.encode("0123456701234567".getBytes()));
   
    private WebSocketServer server;
    private Socket client;
    private ByteBufferPool bufferPool;
    private Parser parser;

    enum State {OPEN, ICLOSED, OCLOSED}

    @Before
    public void before() throws Exception
    {
        bufferPool = new ArrayByteBufferPool();
        parser = new Parser(bufferPool);
    }
    
    @After
    public void after() throws Exception
    {
        if (server!=null)
            server.stop();
    }

    protected Socket newClient() throws IOException
    {
        @SuppressWarnings("resource")
        Socket client = new Socket("127.0.0.1",server.getLocalPort());
    
        HttpFields fields = new HttpFields();
        fields.add(HttpHeader.HOST, "127.0.0.1");
        fields.add(HttpHeader.UPGRADE, "websocket");
        fields.add(HttpHeader.CONNECTION, "Upgrade");
        fields.add(HttpHeader.SEC_WEBSOCKET_KEY, NON_RANDOM_KEY);
        fields.add(HttpHeader.SEC_WEBSOCKET_VERSION, "13");
        fields.add(HttpHeader.PRAGMA, "no-cache");
        fields.add(HttpHeader.CACHE_CONTROL, "no-cache");
        fields.add(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL,"test");

        client.getOutputStream().write(("GET / HTTP/1.1\r\n" + fields.toString()).getBytes(StandardCharsets.ISO_8859_1));
        
        InputStream in = client.getInputStream();
        
        int state = 0;
        StringBuilder buffer = new StringBuilder();
        while(state<4)
        {
            int i = in.read();
            if (i<0)
                throw new EOFException();
            int b = (byte)(i&0xff);
            buffer.append((char)b);
            switch(state)
            {
                case 0:
                    state = (b=='\r')?1:0;
                    break;
                case 1:
                    state = (b=='\n')?2:0;
                    break;
                case 2:
                    state = (b=='\r')?3:0;
                    break;
                case 3:
                    state = (b=='\n')?4:0;
                    break;
                default:
                    state = 0;
            }
        }
        
        String response = buffer.toString();
        assertThat(response,startsWith("HTTP/1.1 101 Switching Protocols"));
        assertThat(response,containsString("Sec-WebSocket-Protocol: test"));
        assertThat(response,containsString("Sec-WebSocket-Accept: +WahVcVmeMLKQUMm0fvPrjSjwzI="));
              
        client.setSoTimeout(10000);
        return client;
    }

    private Parser.ParsedFrame receiveFrame(InputStream in) throws IOException
    {
        ByteBuffer buffer = bufferPool.acquire(4096,false);
        while(true)
        {
            int p = BufferUtil.flipToFill(buffer);
            int len = in.read(buffer.array(),buffer.arrayOffset()+buffer.position(),buffer.remaining());
            if (len<0)
                return null;
            buffer.position(buffer.position()+len);
            BufferUtil.flipToFlush(buffer,p);
            
            Parser.ParsedFrame frame = parser.parse(buffer);
            if (frame!=null)
                return frame;
        }
    }



    public void setup(State state) throws Exception
    {
        switch (state)
        {
            case OPEN:
            {
                TestFrameHandler serverHandler = new TestFrameHandler();
                server = new WebSocketServer(0, serverHandler);
                server.start();
                client = newClient();

                Thread.sleep(100);

                assertThat(server.handler.getCoreSession().toString(), containsString("OPEN"));
                LOG.info("Server: OPEN");

                break;
            }
            case ICLOSED:
            {
                TestFrameHandler serverHandler = new TestFrameHandler()
                {
                    @Override
                    public void onReceiveFrame(Frame frame, Callback callback)
                    {
                        if (frame.getOpCode() == OpCode.CLOSE)
                        {
                            LOG.info("onFrame: " + BufferUtil.toDetailString(frame.getPayload()));
                            receivedFrames.offer(Frame.copy(frame));
                        }
                        else
                            super.onReceiveFrame(frame,callback);
                    }
                };

                server = new WebSocketServer(0, serverHandler);
                server.start();
                client = newClient();

                Thread.sleep(100);

                server.handler.getCoreSession().demand(1);
                client.getOutputStream().write(RawFrameBuilder.buildClose(new CloseStatus(CloseStatus.NORMAL), true));
                Frame frame = serverHandler.receivedFrames.poll(10,TimeUnit.SECONDS);
                assertNotNull(frame);
                assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.NORMAL));

                assertThat(server.handler.getCoreSession().toString(), containsString("ICLOSED"));
                LOG.info("Server: ICLOSED");

                break;
            }
            case OCLOSED:
            {
                TestFrameHandler serverHandler = new TestFrameHandler();

                server = new WebSocketServer(0, serverHandler);
                server.start();
                client = newClient();
                Thread.sleep(100); // todo need to wait until onOpen is called
                server.sendFrame(CloseStatus.toFrame(CloseStatus.NORMAL));

                Frame frame = receiveFrame(client.getInputStream());
                assertNotNull(frame);
                assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.NORMAL));

                assertThat(server.handler.getCoreSession().toString(), containsString("OCLOSED"));
                LOG.info("Server: OCLOSED");

                break;
            }
        }
    }


    /* ------------------------------------------------------------------------------------------- */

    @Test
    public void clientSendsBadFrame_OPEN() throws Exception
    {
        setup(State.OPEN);

        client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.PONG, "pong frame not masked", false));
        server.handler.getCoreSession().demand(1);
        assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.PROTOCOL));
        assertThat(server.handler.closeStatus.getReason(), containsString("Client MUST mask all frames"));
    }


    @Test
    public void clientSendsBadFrame_OCLOSED() throws Exception
    {
        setup(State.OCLOSED);

        client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.PONG, "pong frame not masked", false));
        server.handler.getCoreSession().demand(1);
        assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.PROTOCOL));
        assertThat(server.handler.closeStatus.getReason(), containsString("Client MUST mask all frames"));
    }


    @Test
    public void clientSendsBadFrame_ICLOSED() throws Exception
    {
        setup(State.ICLOSED);

        client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.PONG, "pong frame not masked", false));
        assertFalse(server.handler.closed.await(1, TimeUnit.SECONDS));
        server.handler.getCoreSession().demand(1);
        assertFalse(server.handler.closed.await(1, TimeUnit.SECONDS));

        server.close();
        assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.NORMAL));
    }

    /* ------------------------------------------------------------------------------------------- */

    @Test
    public void clientAborts_OPEN() throws Exception
    {
        setup(State.OPEN);

        client.close();
        assertFalse(server.handler.closed.await(1, TimeUnit.SECONDS));
        server.handler.getCoreSession().demand(1);
        assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.NO_CLOSE));
        assertThat(server.handler.closeStatus.getReason(), containsString("Read EOF"));
    }


    @Test
    public void clientAborts_OCLOSED() throws Exception
    {
        setup(State.OCLOSED);

        client.close();
        assertFalse(server.handler.closed.await(1, TimeUnit.SECONDS));
        server.handler.getCoreSession().demand(1);
        assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.NO_CLOSE));
        assertThat(server.handler.closeStatus.getReason(), containsString("Read EOF"));
    }


    @Test
    public void clientAborts_ICLOSED() throws Exception
    {
        setup(State.ICLOSED);

        client.close();
        assertFalse(server.handler.closed.await(1, TimeUnit.SECONDS));
        server.handler.getCoreSession().demand(1);
        assertFalse(server.handler.closed.await(1, TimeUnit.SECONDS));
        server.close();
        assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.NORMAL));
    }


    /* ------------------------------------------------------------------------------------------- */


    @Test
    public void onFrameThrows_OPEN() throws Exception
    {
        setup(State.OPEN);

        client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.BINARY, "binary", true));

        try (StacklessLogging stacklessLogging = new StacklessLogging(WebSocketChannel.class))
        {
            server.handler.getCoreSession().demand(1);
            assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        }

        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(server.handler.closeStatus.getReason(), containsString("onReceiveFrame throws for binary frames"));
    }

    @Test
    public void onFrameThrows_OCLOSED() throws Exception
    {
        setup(State.OCLOSED);

        client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.BINARY, "binary", true));

        try (StacklessLogging stacklessLogging = new StacklessLogging(WebSocketChannel.class))
        {
            server.handler.getCoreSession().demand(1);
            assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        }

        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(server.handler.closeStatus.getReason(), containsString("onReceiveFrame throws for binary frames"));
    }


    /* ------------------------------------------------------------------------------------------- */



    static class TestFrameHandler implements FrameHandler
    {
        private CoreSession session;

        protected BlockingQueue<Frame> receivedFrames = new BlockingArrayQueue<>();
        protected CountDownLatch closed = new CountDownLatch(1);
        protected CloseStatus closeStatus = null;

        public CoreSession getCoreSession()
        {
            return session;
        }

        public BlockingQueue<Frame> getFrames()
        {
            return receivedFrames;
        }

        @Override
        public void onOpen(CoreSession coreSession) throws Exception
        {
            LOG.info("onOpen {}", coreSession);
            this.session = coreSession;
        }

        @Override
        public void onReceiveFrame(Frame frame, Callback callback)
        {
            LOG.info("onFrame: " + BufferUtil.toDetailString(frame.getPayload()));
            receivedFrames.offer(Frame.copy(frame));

            if(frame.getOpCode() == OpCode.BINARY)
                throw new IllegalArgumentException("onReceiveFrame throws for binary frames");

            callback.succeeded();
        }

        @Override
        public void onClosed(CloseStatus closeStatus)
        {
            LOG.info("onClosed {}",closeStatus);
            this.closeStatus = closeStatus;
            closed.countDown();
        }

        @Override
        public void onError(Throwable cause) throws Exception
        {
            LOG.info("onError {} ",cause==null?null:cause.toString());
        }

        @Override
        public boolean isDemanding()
        {
            return true;
        }
    }

    static class WebSocketServer extends AbstractLifeCycle
    {
        private static Logger LOG = Log.getLogger(WebSocketServer.class);
        private final Server server;
        private final TestFrameHandler handler;

        public void doStart() throws Exception
        {
            server.start();
        }
        
        public void doStop() throws Exception
        {
            server.stop();
        }

        public int getLocalPort()
        {
            return server.getBean(NetworkConnector.class).getLocalPort();
        }

        public WebSocketServer(int port, TestFrameHandler frameHandler)
        {
            this.handler = frameHandler;
            server = new Server();
            server.getBean(QueuedThreadPool.class).setName("WSCoreServer");
            ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory());

            connector.addBean(new WebSocketPolicy(WebSocketBehavior.SERVER));
            connector.addBean(new RFC6455Handshaker());
            connector.setPort(port);
            connector.setIdleTimeout(1000000);
            server.addConnector(connector);

            ContextHandler context = new ContextHandler("/");
            server.setHandler(context);
            WebSocketNegotiator negotiator =  new TestWebSocketNegotiator(new DecoratedObjectFactory(), new WebSocketExtensionRegistry(), connector.getByteBufferPool(), frameHandler);

            WebSocketUpgradeHandler upgradeHandler = new WebSocketUpgradeHandler(negotiator);
            context.setHandler(upgradeHandler);
            upgradeHandler.setHandler(new AbstractHandler()
            {
                @Override
                public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
                {
                    response.setStatus(200);
                    response.setContentType("text/plain");
                    response.getOutputStream().println("Hello World!");
                    baseRequest.setHandled(true);
                }
            });
        }

        public void sendFrame(Frame frame)
        {
            handler.getCoreSession().sendFrame(frame, Callback.NOOP, BatchMode.OFF);
        }

        public void sendText(String line)
        {
            LOG.info("sending {}...", line);
            Frame frame = new Frame(OpCode.TEXT);
            frame.setFin(true);
            frame.setPayload(line);

            handler.getCoreSession().sendFrame(frame, Callback.NOOP, BatchMode.AUTO);
        }

        public BlockingQueue<Frame> getFrames()
        {
            return handler.getFrames();
        }

        public void close()
        {
            handler.getCoreSession().close(CloseStatus.NORMAL, "WebSocketServer Initiated Close", Callback.NOOP);
        }

        public boolean isOpen()
        {
            return handler.getCoreSession().isOpen();
        }
    }

    static class TestWebSocketNegotiator implements WebSocketNegotiator
    {
        final DecoratedObjectFactory objectFactory;
        final WebSocketExtensionRegistry extensionRegistry;
        final ByteBufferPool bufferPool;
        private final FrameHandler frameHandler;

        public TestWebSocketNegotiator(DecoratedObjectFactory objectFactory, WebSocketExtensionRegistry extensionRegistry, ByteBufferPool bufferPool, FrameHandler frameHandler)
        {
            this.objectFactory = objectFactory;
            this.extensionRegistry = extensionRegistry;
            this.bufferPool = bufferPool;
            this.frameHandler = frameHandler;
        }

        @Override
        public FrameHandler negotiate(Negotiation negotiation) throws IOException
        {
            List<String> offeredSubprotocols = negotiation.getOfferedSubprotocols();
            if (!offeredSubprotocols.contains("test"))
                return null;
            negotiation.setSubprotocol("test");
            return frameHandler;
        }

        @Override
        public WebSocketPolicy getCandidatePolicy()
        {
            return null;
        }

        @Override
        public WebSocketExtensionRegistry getExtensionRegistry()
        {
            return extensionRegistry;
        }

        @Override
        public DecoratedObjectFactory getObjectFactory()
        {
            return objectFactory;
        }

        @Override
        public ByteBufferPool getByteBufferPool()
        {
            return bufferPool;
        }
    }
}
