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

package org.eclipse.jetty.websocket.tests.server;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.toolchain.test.ByteBufferAssert;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.tests.ParserCapture;
import org.eclipse.jetty.websocket.tests.SimpleServletServer;
import org.eclipse.jetty.websocket.tests.UnitGenerator;
import org.eclipse.jetty.websocket.tests.servlets.EchoServlet;
import org.hamcrest.Matchers;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.TestName;

/**
 * Testing against local websocket server using {@link org.eclipse.jetty.server.LocalConnector}
 */
public abstract class AbstractLocalServerCase
{
    protected static SimpleServletServer server;
    protected final Logger LOG;
    @Rule
    public TestName testname = new TestName();
    public UnitGenerator generator = new UnitGenerator(WebSocketPolicy.newClientPolicy());
    
    public AbstractLocalServerCase()
    {
        LOG = Log.getLogger(this.getClass().getName());
    }
    
    /**
     * Make a copy of a byte buffer.
     * <p>
     * This is important in some tests, as the underlying byte buffer contained in a Frame can be modified through
     * masking and make it difficult to compare the results in the fuzzer.
     *
     * @param payload the payload to copy
     * @return a new byte array of the payload contents
     */
    public static ByteBuffer clone(ByteBuffer payload)
    {
        ByteBuffer copy = ByteBuffer.allocate(payload.remaining());
        copy.put(payload.slice());
        copy.flip();
        return copy;
    }
    
    /**
     * Make a copy of a byte buffer.
     * <p>
     * This is important in some tests, as the underlying byte buffer contained in a Frame can be modified through
     * masking and make it difficult to compare the results in the fuzzer.
     *
     * @param payload the payload to copy
     * @return a new byte array of the payload contents
     */
    public static ByteBuffer copyOf(ByteBuffer payload)
    {
        ByteBuffer copy = ByteBuffer.allocate(payload.remaining());
        BufferUtil.clearToFill(copy);
        BufferUtil.put(payload, copy);
        BufferUtil.flipToFlush(copy, 0);
        return copy;
    }
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new EchoServlet());
        server.start();
    }
    
    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    public void addInputInSegments(LocalConnector.LocalEndPoint endPoint, ByteBuffer outgoing, int segmentSize)
    {
        while (outgoing.remaining() > 0)
        {
            int len = Math.min(segmentSize, outgoing.remaining());
            ByteBuffer slice = outgoing.slice();
            slice.limit(len);
            endPoint.addInput(slice);
            outgoing.position(outgoing.position() + len);
        }
    }
    
    public void assertExpected(BlockingQueue<WebSocketFrame> framesQueue, List<WebSocketFrame> expect) throws InterruptedException
    {
        int expectedCount = expect.size();
        
        String prefix;
        for (int i = 0; i < expectedCount; i++)
        {
            prefix = "Frame[" + i + "]";
            
            WebSocketFrame expected = expect.get(i);
            WebSocketFrame actual = framesQueue.poll(1, TimeUnit.SECONDS);
            assertThat(prefix + ".poll", actual, notNullValue());
            
            if (LOG.isDebugEnabled())
            {
                if (actual.getOpCode() == OpCode.CLOSE)
                    LOG.debug("{} CloseFrame: {}", prefix, new CloseInfo(actual));
                else
                    LOG.debug("{} {}", prefix, actual);
            }
            
            assertThat(prefix + ".opcode", OpCode.name(actual.getOpCode()), Matchers.is(OpCode.name(expected.getOpCode())));
            prefix += "/" + actual.getOpCode();
            if (expected.getOpCode() == OpCode.CLOSE)
            {
                CloseInfo expectedClose = new CloseInfo(expected);
                CloseInfo actualClose = new CloseInfo(actual);
                assertThat(prefix + ".statusCode", actualClose.getStatusCode(), Matchers.is(expectedClose.getStatusCode()));
            }
            else if (expected.hasPayload())
            {
                assertThat(prefix + ".payloadLength", actual.getPayloadLength(), Matchers.is(expected.getPayloadLength()));
                ByteBufferAssert.assertEquals(prefix + ".payload", expected.getPayload(), actual.getPayload());
            }
            else
            {
                assertThat(prefix + ".payloadLength", actual.getPayloadLength(), Matchers.is(0));
            }
        }
    }
    
    /**
     * Make a copy of a byte buffer.
     * <p>
     * This is important in some tests, as the underlying byte buffer contained in a Frame can be modified through
     * masking and make it difficult to compare the results in the fuzzer.
     *
     * @param payload the payload to copy
     * @return a new byte array of the payload contents
     */
    public ByteBuffer copyOf(byte[] payload)
    {
        return ByteBuffer.wrap(Arrays.copyOf(payload, payload.length));
    }
    
    public String generateUpgradeRequest(String requestPath, Map<String, String> headers)
    {
        StringBuilder upgradeRequest = new StringBuilder();
        upgradeRequest.append("GET ").append(requestPath).append(" HTTP/1.1\r\n");
        headers.entrySet().stream().forEach(e ->
                upgradeRequest.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n"));
        upgradeRequest.append("\r\n");
        return upgradeRequest.toString();
    }
    
    public String generateUpgradeRequest()
    {
        return generateUpgradeRequest("/", newDefaultUpgradeRequestHeaders());
    }
    
    public String generateUpgradeRequest(String requestPath)
    {
        return generateUpgradeRequest(requestPath, newDefaultUpgradeRequestHeaders());
    }
    
    public Parser newClientParser(Parser.Handler parserHandler)
    {
        return new Parser(WebSocketPolicy.newClientPolicy(), new MappedByteBufferPool(), parserHandler);
    }
    
    public Map<String, String> newDefaultUpgradeRequestHeaders()
    {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("Host", "local");
        headers.put("Connection", "Upgrade");
        headers.put("Upgrade", "WebSocket");
        headers.put("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==");
        headers.put("Sec-WebSocket-Origin", "ws://local/");
        headers.put("Sec-WebSocket-Protocol", "echo");
        headers.put("Sec-WebSocket-Version", "13");
        return headers;
    }
    
    public LocalConnector.LocalEndPoint newLocalConnection()
    {
        LocalConnector connector = server.getLocalConnector();
        LocalConnector.LocalEndPoint endPoint = connector.connect();
        return endPoint;
    }
    
    public LocalFuzzer newLocalFuzzer() throws Exception
    {
        return new LocalFuzzer(this);
    }
    
    public HttpTester.Response performUpgrade(LocalConnector.LocalEndPoint endPoint, ByteBuffer buf) throws Exception
    {
        endPoint.addInput(buf);
        
        // Get response
        ByteBuffer response = endPoint.waitForResponse(false, 1, TimeUnit.SECONDS);
        HttpTester.Response parsedResponse = HttpTester.parseResponse(response);
        
        assertThat("Is Switching Protocols", parsedResponse.getStatus(), is(101));
        assertThat("Is WebSocket Upgrade", parsedResponse.get("Upgrade"), is("WebSocket"));
        return parsedResponse;
    }
    
    public static class LocalFuzzer implements AutoCloseable
    {
        private final AbstractLocalServerCase testcase;
        private final LocalConnector.LocalEndPoint endPoint;
        
        public LocalFuzzer(AbstractLocalServerCase testcase) throws Exception
        {
            this.testcase = testcase;
            String upgradeRequest = testcase.generateUpgradeRequest("/");
            ByteBuffer upgradeRequestBytes = BufferUtil.toBuffer(upgradeRequest.toString(), StandardCharsets.UTF_8);
            this.endPoint = testcase.newLocalConnection();
            testcase.performUpgrade(endPoint, upgradeRequestBytes);
        }
        
        public ByteBuffer asNetworkBuffer(List<WebSocketFrame> frames)
        {
            int bufferLength = 0;
            for (WebSocketFrame f : frames)
            {
                bufferLength += f.getPayloadLength() + Generator.MAX_HEADER_LENGTH;
            }
            
            ByteBuffer buffer = ByteBuffer.allocate(bufferLength);
            
            for (WebSocketFrame f : frames)
            {
                testcase.generator.generate(buffer, f);
            }
            BufferUtil.flipToFlush(buffer, 0);
            return buffer;
        }
        
        public void close() throws Exception
        {
            endPoint.close();
        }
        
        /**
         * Send the EOF signal
         */
        public void eof()
        {
            endPoint.addInputEOF();
        }
        
        public void expect(List<WebSocketFrame> expected) throws InterruptedException
        {
            // Get incoming frames
            // Wait for server to close
            endPoint.waitUntilClosed();
            
            // Get the server send echo bytes
            ByteBuffer incoming = endPoint.getOutput();
            
            // Parse those bytes into frames
            ParserCapture capture = new ParserCapture();
            Parser parser = testcase.newClientParser(capture);
            parser.parse(incoming);
            
            testcase.assertExpected(capture.framesQueue, expected);
        }
        
        /**
         * Send raw bytes
         */
        public void send(ByteBuffer buffer)
        {
            endPoint.addInput(buffer);
        }
        
        /**
         * Send some of the raw bytes
         *
         * @param buffer the buffer
         * @param length the number of bytes to send from buffer
         */
        public void send(ByteBuffer buffer, int length)
        {
            int limit = Math.min(length, buffer.remaining());
            ByteBuffer sliced = buffer.slice();
            sliced.limit(limit);
            endPoint.addInput(sliced);
            buffer.position(buffer.position() + limit);
        }
        
        /**
         * Generate a single ByteBuffer representing the entire
         * list of generated frames, and submit it to {@link org.eclipse.jetty.server.LocalConnector.LocalEndPoint#addInput(ByteBuffer)}
         *
         * @param frames the list of frames to send
         */
        public void sendBulk(List<WebSocketFrame> frames)
        {
            int bufferLength = 0;
            for (WebSocketFrame f : frames)
            {
                bufferLength += f.getPayloadLength() + Generator.MAX_HEADER_LENGTH;
            }
            
            ByteBuffer outgoing = ByteBuffer.allocate(bufferLength);
            
            boolean eof = false;
            for (WebSocketFrame f : frames)
            {
                testcase.generator.generate(outgoing, f);
                if (f.getOpCode() == OpCode.CLOSE)
                    eof = true;
            }
            BufferUtil.flipToFlush(outgoing, 0);
            endPoint.addInput(outgoing);
            if (eof)
                endPoint.addInputEOF();
        }
        
        /**
         * Generate a ByteBuffer for each frame, and submit each to
         * {@link org.eclipse.jetty.server.LocalConnector.LocalEndPoint#addInput(ByteBuffer)}
         *
         * @param frames the list of frames to send
         */
        public void sendFrames(List<WebSocketFrame> frames)
        {
            boolean eof = false;
            for (WebSocketFrame f : frames)
            {
                ByteBuffer buffer = testcase.generator.generate(f);
                endPoint.addInput(buffer);
                if (f.getOpCode() == OpCode.CLOSE)
                    eof = true;
            }
            
            if (eof)
                endPoint.addInputEOF();
        }
        
        /**
         * Generate a ByteBuffer for each frame, and submit each to
         * {@link org.eclipse.jetty.server.LocalConnector.LocalEndPoint#addInput(ByteBuffer)}
         *
         * @param frames the list of frames to send
         */
        public void sendSegmented(List<WebSocketFrame> frames, int segmentSize)
        {
            int bufferLength = 0;
            for (WebSocketFrame f : frames)
            {
                bufferLength += f.getPayloadLength() + Generator.MAX_HEADER_LENGTH;
            }
            
            ByteBuffer outgoing = ByteBuffer.allocate(bufferLength);
            
            boolean eof = false;
            for (WebSocketFrame f : frames)
            {
                testcase.generator.generate(outgoing, f);
                if (f.getOpCode() == OpCode.CLOSE)
                    eof = true;
            }
            BufferUtil.flipToFlush(outgoing, 0);
            testcase.addInputInSegments(endPoint, outgoing, segmentSize);
            if (eof)
                endPoint.addInputEOF();
        }
    }
}
