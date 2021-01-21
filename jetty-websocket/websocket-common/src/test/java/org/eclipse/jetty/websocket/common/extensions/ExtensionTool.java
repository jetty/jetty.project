//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.common.extensions;

import java.nio.ByteBuffer;
import java.util.Collections;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Extension;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.ExtensionFactory;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.scopes.SimpleContainerScope;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.eclipse.jetty.websocket.common.test.ByteBufferAssert;
import org.eclipse.jetty.websocket.common.test.IncomingFramesCapture;
import org.eclipse.jetty.websocket.common.test.UnitParser;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

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

            this.parser = new UnitParser(policy);
        }

        public String getRequestedExtParams()
        {
            return requestedExtParams;
        }

        public void assertNegotiated(String expectedNegotiation)
        {
            this.ext = (Extension)factory.newInstance(extConfig);

            this.capture = new IncomingFramesCapture();
            this.ext.setNextIncomingFrames(capture);

            this.parser.configureFromExtensions(Collections.singletonList(ext));
            this.parser.setIncomingFramesHandler(ext);
        }

        public void parseIncomingHex(String... rawhex)
        {
            int parts = rawhex.length;
            byte[] net;

            for (int i = 0; i < parts; i++)
            {
                String hex = rawhex[i].replaceAll("\\s*(0x)?", "");
                net = TypeUtil.fromHexString(hex);
                parser.parse(ByteBuffer.wrap(net));
            }
        }

        public void assertHasFrames(String... textFrames)
        {
            Frame[] frames = new Frame[textFrames.length];
            for (int i = 0; i < frames.length; i++)
            {
                frames[i] = new TextFrame().setPayload(textFrames[i]);
            }
            assertHasFrames(frames);
        }

        public void assertHasFrames(Frame... expectedFrames)
        {
            int expectedCount = expectedFrames.length;
            capture.assertFrameCount(expectedCount);

            for (int i = 0; i < expectedCount; i++)
            {
                WebSocketFrame actual = capture.getFrames().poll();

                String prefix = String.format("frame[%d]", i);
                assertThat(prefix + ".opcode", actual.getOpCode(), is(expectedFrames[i].getOpCode()));
                assertThat(prefix + ".fin", actual.isFin(), is(expectedFrames[i].isFin()));
                assertThat(prefix + ".rsv1", actual.isRsv1(), is(false));
                assertThat(prefix + ".rsv2", actual.isRsv2(), is(false));
                assertThat(prefix + ".rsv3", actual.isRsv3(), is(false));

                ByteBuffer expected = expectedFrames[i].getPayload().slice();
                assertThat(prefix + ".payloadLength", actual.getPayloadLength(), is(expected.remaining()));
                ByteBufferAssert.assertEquals(prefix + ".payload", expected, actual.getPayload().slice());
            }
        }
    }

    private final WebSocketPolicy policy;
    private final ExtensionFactory factory;

    public ExtensionTool(WebSocketPolicy policy, ByteBufferPool bufferPool)
    {
        this.policy = policy;
        WebSocketContainerScope container = new SimpleContainerScope(policy, bufferPool);
        WebSocketExtensionFactory extFactory = new WebSocketExtensionFactory(container);
        this.factory = extFactory;
    }

    public Tester newTester(String parameterizedExtension)
    {
        return new Tester(parameterizedExtension);
    }
}
