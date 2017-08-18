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

package org.eclipse.jetty.websocket.core.extensions;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.nio.ByteBuffer;
import java.util.Collections;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.toolchain.test.ByteBufferAssert;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.WSPolicy;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.frames.WSFrame;
import org.eclipse.jetty.websocket.core.io.IncomingFramesCapture;
import org.eclipse.jetty.websocket.core.Parser;
import org.hamcrest.Matchers;

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
            this.parser = new Parser(policy, new MappedByteBufferPool(), frame ->
            {
                ext.incomingFrame(frame, Callback.NOOP);
                return true;
            });
        }

        public String getRequestedExtParams()
        {
            return requestedExtParams;
        }

        public void assertNegotiated(String expectedNegotiation)
        {
            this.ext = factory.newInstance(objectFactory, policy, bufferPool, extConfig);
            this.ext.setNextIncomingFrames(capture);

            this.parser.configureFromExtensions(Collections.singletonList(ext));
        }
    
        public void parseIncomingHex(String... rawhex)
        {
            int parts = rawhex.length;
            byte net[];

            for (int i = 0; i < parts; i++)
            {
                String hex = rawhex[i].replaceAll("\\s*(0x)?","");
                net = TypeUtil.fromHexString(hex);
                parser.parse(ByteBuffer.wrap(net));
            }
        }

        public void assertHasFrames(String... textFrames)
        {
            Frame frames[] = new Frame[textFrames.length];
            for (int i = 0; i < frames.length; i++)
            {
                frames[i] = new TextFrame().setPayload(textFrames[i]);
            }
            assertHasFrames(frames);
        }

        public void assertHasFrames(Frame... expectedFrames)
        {
            int expectedCount = expectedFrames.length;
            assertThat("Frame Count", capture.frames.size(), is(expectedCount));

            for (int i = 0; i < expectedCount; i++)
            {
                WSFrame actual = capture.frames.poll();

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
    private final WSPolicy policy;
    private final ByteBufferPool bufferPool;
    private final WSExtensionFactory factory;

    public ExtensionTool(WSPolicy policy, ByteBufferPool bufferPool)
    {
        this.objectFactory = new DecoratedObjectFactory();
        this.policy = policy;
        this.bufferPool = bufferPool;
        this.factory = new WSExtensionFactory();
    }

    public Tester newTester(String parameterizedExtension)
    {
        return new Tester(parameterizedExtension);
    }
}
