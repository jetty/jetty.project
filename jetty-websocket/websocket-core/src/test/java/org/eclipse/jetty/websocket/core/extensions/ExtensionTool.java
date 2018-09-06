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

package org.eclipse.jetty.websocket.core.extensions;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.toolchain.test.ByteBufferAssert;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.websocket.core.Parser;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.frames.Frame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.io.IncomingFramesCapture;
import org.hamcrest.Matchers;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

public class ExtensionTool
{
    public class Tester
    {
        private String requestedExtParams;
        private ExtensionConfig extConfig;
        private Extension ext;
        private Parser parser;
        private IncomingFramesCapture capture;

        private Tester(String parameterizedExtension)
        {
            this.requestedExtParams = parameterizedExtension;
            this.extConfig = ExtensionConfig.parse(parameterizedExtension);
            Class<?> extClass = factory.getExtension(extConfig.getName());
            assertThat("extClass", extClass, notNullValue());
    
            this.capture = new IncomingFramesCapture();
            this.parser = new Parser(policy, new MappedByteBufferPool());
        }

        public String getRequestedExtParams()
        {
            return requestedExtParams;
        }

        public void assertNegotiated(String expectedNegotiation)
        {
            this.ext = factory.newInstance(objectFactory, policy, bufferPool, extConfig);
            this.ext.setNextIncomingFrames(capture);
        }
    
        public void parseIncomingHex(String... rawhex)
        {
            int parts = rawhex.length;
            byte net[];

            for (int i = 0; i < parts; i++)
            {
                String hex = rawhex[i].replaceAll("\\s*(0x)?","");
                net = TypeUtil.fromHexString(hex);

                ByteBuffer buffer = ByteBuffer.wrap(net);
                while (BufferUtil.hasContent(buffer))
                {
                    Frame frame = parser.parse(buffer);
                    if (frame==null)
                        break;
                    ext.onReceiveFrame(frame,Callback.NOOP);
                }                
            }
        }

        public void assertHasFrames(String... textFrames)
        {
            org.eclipse.jetty.websocket.core.frames.Frame frames[] = new org.eclipse.jetty.websocket.core.frames.Frame[textFrames.length];
            for (int i = 0; i < frames.length; i++)
            {
                frames[i] = new Frame(OpCode.TEXT).setPayload(textFrames[i]);
            }
            assertHasFrames(frames);
        }

        public void assertHasFrames(org.eclipse.jetty.websocket.core.frames.Frame... expectedFrames)
        {
            int expectedCount = expectedFrames.length;
            assertThat("Frame Count", capture.frames.size(), is(expectedCount));

            for (int i = 0; i < expectedCount; i++)
            {
                Frame actual = capture.frames.poll();

                String prefix = String.format("frame[%d]",i);
                assertThat(prefix + ".opcode",actual.getOpCode(), Matchers.is(expectedFrames[i].getOpCode()));
                assertThat(prefix + ".fin",actual.isFin(), Matchers.is(expectedFrames[i].isFin()));
                assertThat(prefix + ".rsv1",actual.isRsv1(),is(false));
                assertThat(prefix + ".rsv2",actual.isRsv2(),is(false));
                assertThat(prefix + ".rsv3",actual.isRsv3(),is(false));

                ByteBuffer expected = expectedFrames[i].getPayload().slice();
                assertThat(prefix + ".payloadLength",actual.getPayloadLength(),is(expected.remaining()));
                ByteBufferAssert.assertEquals(prefix + ".payload",expected,actual.getPayload().slice());
            }
        }
    }

    private final DecoratedObjectFactory objectFactory;
    private final WebSocketPolicy policy;
    private final ByteBufferPool bufferPool;
    private final WebSocketExtensionRegistry factory;

    public ExtensionTool(WebSocketPolicy policy, ByteBufferPool bufferPool)
    {
        this.objectFactory = new DecoratedObjectFactory();
        this.policy = policy;
        this.bufferPool = bufferPool;
        this.factory = new WebSocketExtensionRegistry();
    }

    public Tester newTester(String parameterizedExtension)
    {
        return new Tester(parameterizedExtension);
    }
}
