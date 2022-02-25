//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.extensions;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.toolchain.test.ByteBufferAssert;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.Extension;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.IncomingFramesCapture;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.TestMessageHandler;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.internal.ExtensionStack;
import org.eclipse.jetty.websocket.core.internal.Negotiated;
import org.eclipse.jetty.websocket.core.internal.Parser;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class ExtensionTool
{
    public class Tester
    {
        private final String requestedExtParams;
        private final ExtensionConfig extConfig;
        private Extension ext;
        private final Parser parser;
        private final IncomingFramesCapture capture;
        private final WebSocketCoreSession coreSession;

        private Tester(String parameterizedExtension)
        {
            this.requestedExtParams = parameterizedExtension;
            this.extConfig = ExtensionConfig.parse(parameterizedExtension);
            coreSession = newWebSocketCoreSession(Collections.singletonList(extConfig));
            ExtensionStack extensionStack = coreSession.getExtensionStack();
            assertThat(extensionStack.getExtensions().size(), equalTo(1));

            this.capture = new IncomingFramesCapture();
            this.parser = new Parser(new MappedByteBufferPool());
        }

        public String getRequestedExtParams()
        {
            return requestedExtParams;
        }

        public void assertNegotiated(String expectedNegotiation)
        {
            this.ext = coreSession.getExtensionStack().getExtensions().get(0);
            this.ext.setNextIncomingFrames(capture);
        }

        public void parseIncomingHex(String... rawhex)
        {
            int parts = rawhex.length;
            byte[] net;

            // Simulate initial demand from onOpen().
            coreSession.autoDemand();

            for (int i = 0; i < parts; i++)
            {
                String hex = rawhex[i].replaceAll("\\s*(0x)?", "");
                net = TypeUtil.fromHexString(hex);

                ByteBuffer buffer = ByteBuffer.wrap(net);
                while (BufferUtil.hasContent(buffer))
                {
                    Frame frame = parser.parse(buffer);
                    if (frame == null)
                        break;

                    FutureCallback callback = new FutureCallback()
                    {
                        @Override
                        public void succeeded()
                        {
                            super.succeeded();
                            if (!coreSession.isDemanding())
                                coreSession.autoDemand();
                        }
                    };
                    ext.onFrame(frame, callback);

                    // Throw if callback fails.
                    try
                    {
                        callback.get();
                    }
                    catch (ExecutionException e)
                    {
                        throw new RuntimeException(e.getCause());
                    }
                    catch (Throwable t)
                    {
                        Assertions.fail(t);
                    }
                }
            }
        }

        public void assertHasFrames(String... textFrames)
        {
            Frame[] frames = new Frame[textFrames.length];
            for (int i = 0; i < frames.length; i++)
            {
                frames[i] = new Frame(OpCode.TEXT).setPayload(textFrames[i]);
            }
            assertHasFrames(frames);
        }

        public void assertHasFrames(Frame... expectedFrames)
        {
            int expectedCount = expectedFrames.length;
            assertThat("Frame Count", capture.frames.size(), is(expectedCount));

            for (int i = 0; i < expectedCount; i++)
            {
                Frame actual = capture.frames.poll();

                String prefix = String.format("frame[%d]", i);
                assertThat(prefix + ".opcode", actual.getOpCode(), Matchers.is(expectedFrames[i].getOpCode()));
                assertThat(prefix + ".fin", actual.isFin(), Matchers.is(expectedFrames[i].isFin()));
                assertThat(prefix + ".rsv1", actual.isRsv1(), is(false));
                assertThat(prefix + ".rsv2", actual.isRsv2(), is(false));
                assertThat(prefix + ".rsv3", actual.isRsv3(), is(false));

                ByteBuffer expected = expectedFrames[i].getPayload().slice();
                assertThat(prefix + ".payloadLength", actual.getPayloadLength(), is(expected.remaining()));
                ByteBufferAssert.assertEquals(prefix + ".payload", expected, actual.getPayload().slice());
            }
        }
    }

    private final WebSocketComponents components;

    public ExtensionTool(ByteBufferPool bufferPool)
    {
        this.components = new WebSocketComponents();
    }

    public Tester newTester(String parameterizedExtension)
    {
        return new Tester(parameterizedExtension);
    }

    private WebSocketCoreSession newWebSocketCoreSession(List<ExtensionConfig> configs)
    {
        ExtensionStack exStack = new ExtensionStack(components, Behavior.SERVER);
        exStack.setLastDemand(l -> {}); // Never delegate to WebSocketConnection as it is null for this test.
        exStack.negotiate(configs, configs);
        return new WebSocketCoreSession(new TestMessageHandler(), Behavior.SERVER, Negotiated.from(exStack), components);
    }
}
