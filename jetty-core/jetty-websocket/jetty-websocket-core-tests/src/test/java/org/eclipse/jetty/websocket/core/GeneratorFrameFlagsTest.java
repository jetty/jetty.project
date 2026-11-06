//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core;

import java.util.LinkedList;
import java.util.stream.Stream;

import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.websocket.core.exception.ProtocolException;
import org.eclipse.jetty.websocket.core.internal.Generator;
import org.eclipse.jetty.websocket.core.internal.ParsedFrame;
import org.eclipse.jetty.websocket.core.util.FrameValidation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test various invalid frame situations
 */
public class GeneratorFrameFlagsTest
{
    private static final WebSocketComponents components = new WebSocketComponents();
    private WebSocketCoreSession coreSession;

    public static Stream<Arguments> data()
    {
        return Stream.of(
            Arguments.of(new Frame(OpCode.PING, false, "")),
            Arguments.of(new Frame(OpCode.PING, true, true, RetainableByteBuffer.empty())),
            Arguments.of(new ParsedFrame((byte)(0x80 | 0x20 | OpCode.PING), null, RetainableByteBuffer.empty())),
            Arguments.of(new ParsedFrame((byte)(0x80 | 0x10 | OpCode.PING), null, RetainableByteBuffer.empty())),
            Arguments.of(new Frame(OpCode.PONG, false, "")),
            Arguments.of(new Frame(OpCode.PING, true, true, RetainableByteBuffer.empty())),
            Arguments.of(new ParsedFrame((byte)(0x80 | 0x20 | OpCode.PONG), null, RetainableByteBuffer.empty())),
            Arguments.of(new ParsedFrame((byte)(0x80 | 0x10 | OpCode.PONG), null, RetainableByteBuffer.empty())),
            Arguments.of(new Frame(OpCode.CLOSE, false, "")),
            Arguments.of(new Frame(OpCode.CLOSE, true, true, RetainableByteBuffer.empty())),
            Arguments.of(new ParsedFrame((byte)(0x80 | 0x20 | OpCode.CLOSE), null, RetainableByteBuffer.empty())),
            Arguments.of(new ParsedFrame((byte)(0x80 | 0x10 | OpCode.CLOSE), null, RetainableByteBuffer.empty()))
        );
    }

    public void setup(Frame invalidFrame)
    {
        ExtensionStack exStack = new ExtensionStack(components, Behavior.SERVER);
        exStack.negotiate(new LinkedList<>(), new LinkedList<>());
        this.coreSession = new WebSocketCoreSession(new TestMessageHandler(), Behavior.CLIENT, Negotiated.from(exStack), components);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testGenerateInvalidControlFrame(Frame invalidFrame)
    {
        setup(invalidFrame);

        RetainableByteBuffer.Mutable buffer = RetainableByteBuffer.Mutable.allocate(100, false);
        new Generator().generateWholeFrame(invalidFrame, buffer);
        assertThrows(ProtocolException.class, () -> FrameValidation.assertValidOutgoing(invalidFrame, coreSession));
    }
}
