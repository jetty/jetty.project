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

package org.eclipse.jetty.websocket.common;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;

/**
 * Useful for testing the production of sane frame ordering from various components.
 */
public class SaneFrameOrderingAssertion implements OutgoingFrames
{
    boolean priorDataFrame = false;
    public int frameCount = 0;

    @Override
    public void outgoingFrame(Frame frame, WriteCallback callback, BatchMode batchMode)
    {
        byte opcode = frame.getOpCode();
        assertThat("OpCode.isKnown(" + opcode + ")", OpCode.isKnown(opcode), is(true));

        switch (opcode)
        {
            case OpCode.TEXT:
                assertFalse("Unexpected " + OpCode.name(opcode) + " frame, was expecting CONTINUATION", priorDataFrame);
                break;
            case OpCode.BINARY:
                assertFalse("Unexpected " + OpCode.name(opcode) + " frame, was expecting CONTINUATION", priorDataFrame);
                break;
            case OpCode.CONTINUATION:
                assertTrue("CONTINUATION frame without prior !FIN", priorDataFrame);
                break;
            case OpCode.CLOSE:
                assertFalse("Fragmented Close Frame [" + OpCode.name(opcode) + "]", frame.isFin());
                break;
            case OpCode.PING:
                assertFalse("Fragmented Close Frame [" + OpCode.name(opcode) + "]", frame.isFin());
                break;
            case OpCode.PONG:
                assertFalse("Fragmented Close Frame [" + OpCode.name(opcode) + "]", frame.isFin());
                break;
        }

        if (OpCode.isDataFrame(opcode))
        {
            priorDataFrame = !frame.isFin();
        }

        frameCount++;

        if (callback != null)
            callback.writeSuccess();
    }
}
