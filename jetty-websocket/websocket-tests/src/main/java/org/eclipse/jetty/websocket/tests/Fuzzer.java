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

package org.eclipse.jetty.websocket.tests;

import static org.eclipse.jetty.websocket.tests.Fuzzer.SendMode.BULK;
import static org.eclipse.jetty.websocket.tests.Fuzzer.SendMode.PER_FRAME;
import static org.eclipse.jetty.websocket.tests.Fuzzer.SendMode.SLOW;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.ByteBufferAssert;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.FrameCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;

/**
 * Fuzzing utility for the AB tests.
 */
public class Fuzzer extends ContainerLifeCycle
{
    public static class BlockerCallback implements FrameCallback
    {
        private CompletableFuture<Void> future = new CompletableFuture<>();
        
        @Override
        public void fail(Throwable cause)
        {
            future.completeExceptionally(cause);
        }
    
        @Override
        public void succeed()
        {
            future.complete(null);
        }
        
        public void block() throws Exception
        {
            future.get(1, TimeUnit.MINUTES);
        }
    }
    
    public static class Session implements AutoCloseable
    {
        // Client side framing mask
        private static final byte[] MASK = {0x11, 0x22, 0x33, 0x44};
        
        private final Fuzzed testcase;
        private final UntrustedWSSession session;
        private final Generator generator;
        private SendMode sendMode = SendMode.BULK;
        private int slowSendSegmentSize = 5;
        private boolean ignoreBrokenPipe = false;
        
        public Session(Fuzzed testcase, UntrustedWSSession session)
        {
            this.testcase = testcase;
            this.session = session;
            this.generator = testcase.getLaxGenerator();
        }
        
        @Override
        public void close() throws Exception
        {
            session.close();
        }
        
        public Session slowMode(int slowSendSegmentSize)
        {
            this.sendMode = SLOW;
            this.slowSendSegmentSize = slowSendSegmentSize;
            return this;
        }
        
        public Session bulkMode()
        {
            this.sendMode = BULK;
            return this;
        }
        
        public Session perFrameMode()
        {
            this.sendMode = PER_FRAME;
            return this;
        }
        
        public Session ignoreBrokenPipe()
        {
            this.ignoreBrokenPipe = true;
            return this;
        }
        
        private void assertIsOpen() throws Exception
        {
            assertThat("Session exists", session, notNullValue());
            assertThat("Session is open", session.isOpen(), is(true));
            assertThat("Endpoint is open", session.getUntrustedEndpoint().openLatch.await(5, TimeUnit.SECONDS), is(true));
        }
        
        public ByteBuffer asNetworkBuffer(List<WebSocketFrame> send)
        {
            int buflen = 0;
            for (Frame f : send)
            {
                buflen += f.getPayloadLength() + Generator.MAX_HEADER_LENGTH;
            }
            ByteBuffer buf = session.getBufferPool().acquire(buflen, false);
            BufferUtil.clearToFill(buf);
            
            // Generate frames
            for (WebSocketFrame f : send)
            {
                setClientMask(f);
                generator.generateWholeFrame(f, buf);
            }
            buf.flip();
            return buf;
        }
        
        private void setClientMask(WebSocketFrame f)
        {
            if (LOG.isDebugEnabled())
            {
                f.setMask(new byte[]
                        {0x00, 0x00, 0x00, 0x00});
            }
            else
            {
                f.setMask(MASK); // make sure we have mask set
            }
        }
        
        public void expect(List<WebSocketFrame> expect) throws Exception
        {
            expect(expect, 10, TimeUnit.SECONDS);
        }
        
        public void expect(List<WebSocketFrame> expect, int duration, TimeUnit unit) throws Exception
        {
            int expectedCount = expect.size();
            LOG.debug("expect() {} frame(s)", expect.size());
            
            // Read frames
            Future<List<WebSocketFrame>> futFrames = session.getUntrustedEndpoint().expectedFrames(expectedCount);
            
            List<WebSocketFrame> frames = futFrames.get(duration, unit);
            
            String prefix = "";
            for (int i = 0; i < expectedCount; i++)
            {
                WebSocketFrame expected = expect.get(i);
                WebSocketFrame actual = frames.get(i);
                
                prefix = "Frame[" + i + "]";
                
                LOG.debug("{} {}", prefix, actual);
                
                assertThat(prefix + ".opcode", OpCode.name(actual.getOpCode()), is(OpCode.name(expected.getOpCode())));
                prefix += "/" + actual.getOpCode();
                if (expected.getOpCode() == OpCode.CLOSE)
                {
                    CloseInfo expectedClose = new CloseInfo(expected);
                    CloseInfo actualClose = new CloseInfo(actual);
                    assertThat(prefix + ".statusCode", actualClose.getStatusCode(), is(expectedClose.getStatusCode()));
                }
                else
                {
                    assertThat(prefix + ".payloadLength", actual.getPayloadLength(), is(expected.getPayloadLength()));
                    ByteBufferAssert.assertEquals(prefix + ".payload", expected.getPayload(), actual.getPayload());
                }
            }
        }
        
        public void expect(WebSocketFrame expect) throws Exception
        {
            expect(Collections.singletonList(expect));
        }
        
        public Session send(WebSocketFrame send) throws Exception
        {
            send(Collections.singletonList(send));
            return this;
        }
        
        public Session send(ByteBuffer buf) throws Exception
        {
            assertIsOpen();
            LOG.debug("Sending bytes {}", BufferUtil.toDetailString(buf));
            if (sendMode == SLOW)
            {
                session.getUntrustedConnection().writeRawSlowly(buf, slowSendSegmentSize);
            }
            else
            {
                session.getUntrustedConnection().writeRaw(buf);
            }
            return this;
        }
        
        public Session send(ByteBuffer buf, int numBytes) throws IOException
        {
            session.getUntrustedConnection().writeRaw(buf, numBytes);
            return this;
        }
        
        public Session send(List<WebSocketFrame> send) throws Exception
        {
            assertIsOpen();
            LOG.debug("[{}] Sending {} frames (mode {})", testcase.getTestMethodName(), send.size(), sendMode);
            
            try
            {
                for (WebSocketFrame f : send)
                {
                    BlockerCallback blocker = new BlockerCallback();
                    session.getOutgoingHandler().outgoingFrame(f, blocker, BatchMode.OFF);
                    blocker.block();
                }
            }
            catch (SocketException e)
            {
                if (ignoreBrokenPipe)
                {
                    // Potential for SocketException (Broken Pipe) here.
                    // But not in 100% of testing scenarios. It is a safe
                    // exception to ignore in this testing scenario, as the
                    // slow writing of the frames can result in the server
                    // throwing a PROTOCOL ERROR termination/close when it
                    // encounters the bad continuation frame above (this
                    // termination is the expected behavior), and this
                    // early socket close can propagate back to the client
                    // before it has a chance to finish writing out the
                    // remaining frame octets
                    assertThat("Allowed to be a broken pipe", e.getMessage().toLowerCase(Locale.ENGLISH), containsString("broken pipe"));
                }
                else
                {
                    throw e;
                }
            }
            return this;
        }
    }
    
    public enum SendMode
    {
        BULK,
        PER_FRAME,
        SLOW
    }
    
    public enum DisconnectMode
    {
        /** Disconnect occurred after a proper close handshake */
        CLEAN,
        /** Disconnect occurred in a harsh manner, without a close handshake */
        UNCLEAN
    }
    
    private static final int KBYTE = 1024;
    private static final int MBYTE = KBYTE * KBYTE;
    
    private static final Logger LOG = Log.getLogger(Fuzzer.class);
    
    private final UntrustedWSClient client;
    
    private long connectTimeout = 2;
    private TimeUnit connectTimeoutUnit = TimeUnit.SECONDS;
    
    public Fuzzer() throws Exception
    {
        this.client = new UntrustedWSClient();
        
        int bigMessageSize = 20 * MBYTE;
        
        this.client.getPolicy().setMaxTextMessageSize(bigMessageSize);
        this.client.getPolicy().setMaxBinaryMessageSize(bigMessageSize);
        this.client.getPolicy().setIdleTimeout(5000);
        
        this.client.setMaxIdleTimeout(TimeUnit.SECONDS.toMillis(2));
        
        addBean(this.client);
    }
    
    public UntrustedWSClient getWSClient()
    {
        return this.client;
    }
    
    public Fuzzer.Session connect(Fuzzed testcase) throws Exception
    {
        // TODO: handle EndPoint behavior here. (BULK/SLOW/FRAME)
        //   BULK = AggregatingEndpoint write (aggregate until .flush() call)
        //   SLOW = FixedBufferEndpoint write (send fixed buffer size)
        //   PERFRAME = No change to Endpoint
        
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.setHeader("X-TestCase", testcase.getTestMethodName());
        UntrustedWSSession session = client.connect(testcase.getServerURI(), upgradeRequest).get(connectTimeout, connectTimeoutUnit);
        return new Fuzzer.Session(testcase, session);
    }
}
