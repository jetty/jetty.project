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

package org.eclipse.jetty.websocket.common.ab;

import java.util.stream.Stream;

import org.eclipse.jetty.websocket.api.ProtocolException;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.PingFrame;
import org.eclipse.jetty.websocket.common.frames.PongFrame;
import org.eclipse.jetty.websocket.common.test.UnitGenerator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test various invalid frame situations
 */
public class TestABCase3
{
    public static Stream<Arguments> badFrames()
    {
        return Stream.of(
            new PingFrame().setFin(false),
            new PingFrame().setRsv1(true),
            new PingFrame().setRsv2(true),
            new PingFrame().setRsv3(true),
            new PongFrame().setFin(false),
            new PingFrame().setRsv1(true),
            new PongFrame().setRsv2(true),
            new PongFrame().setRsv3(true),
            new CloseInfo().asFrame().setFin(false),
            new CloseInfo().asFrame().setRsv1(true),
            new CloseInfo().asFrame().setRsv2(true),
            new CloseInfo().asFrame().setRsv3(true))
            .map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("badFrames")
    public void testGenerateInvalidControlFrame(WebSocketFrame invalidFrame)
    {
        assertThrows(ProtocolException.class, () -> UnitGenerator.generate(invalidFrame));
    }
}
