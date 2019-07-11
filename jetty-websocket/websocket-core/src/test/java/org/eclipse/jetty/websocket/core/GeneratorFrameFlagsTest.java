//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.stream.Stream;

import org.eclipse.jetty.websocket.core.internal.ExtensionStack;
import org.eclipse.jetty.websocket.core.internal.Generator;
import org.eclipse.jetty.websocket.core.internal.Negotiated;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test various invalid frame situations
 */
public class GeneratorFrameFlagsTest
{
    private static WebSocketComponents components = new WebSocketComponents();
    private WebSocketCoreSession coreSession;

    public static Stream<Arguments> data()
    {
        return Stream.of(
            Arguments.of(new Frame(OpCode.PING).setFin(false)),
            Arguments.of(new Frame(OpCode.PING).setRsv1(true)),
            Arguments.of(new Frame(OpCode.PING).setRsv2(true)),
            Arguments.of(new Frame(OpCode.PING).setRsv3(true)),
            Arguments.of(new Frame(OpCode.PONG).setFin(false)),
            Arguments.of(new Frame(OpCode.PING).setRsv1(true)),
            Arguments.of(new Frame(OpCode.PONG).setRsv2(true)),
            Arguments.of(new Frame(OpCode.PONG).setRsv3(true)),
            Arguments.of(new Frame(OpCode.CLOSE).setFin(false)),
            Arguments.of(new Frame(OpCode.CLOSE).setRsv1(true)),
            Arguments.of(new Frame(OpCode.CLOSE).setRsv2(true)),
            Arguments.of(new Frame(OpCode.CLOSE).setRsv3(true))
        );
    }

    public void setup(Frame invalidFrame)
    {
        ExtensionStack exStack = new ExtensionStack(components, Behavior.SERVER);
        exStack.negotiate(new LinkedList<>(), new LinkedList<>());
        this.coreSession = new WebSocketCoreSession(new TestMessageHandler(), Behavior.CLIENT, Negotiated.from(exStack));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testGenerateInvalidControlFrame(Frame invalidFrame)
    {
        setup(invalidFrame);

        ByteBuffer buffer = ByteBuffer.allocate(100);
        new Generator(components.getBufferPool()).generateWholeFrame(invalidFrame, buffer);
        assertThrows(ProtocolException.class, () -> coreSession.assertValidOutgoing(invalidFrame));
    }
}
