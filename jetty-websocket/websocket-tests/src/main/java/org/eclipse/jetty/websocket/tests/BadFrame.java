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

import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.WebSocketFrame;

/**
 * Allow Fuzzer / Generator to create bad frames for testing frame validation
 */
public class BadFrame extends WebSocketFrame
{
    public BadFrame(byte opcode)
    {
        super(OpCode.CONTINUATION);
        super.finRsvOp = (byte) ((finRsvOp & 0xF0) | (opcode & 0x0F));
        // NOTE: Not setting Frame.Type intentionally
    }
    
    @Override
    public void assertValid()
    {
    }
    
    @Override
    public boolean isControlFrame()
    {
        return false;
    }
    
    @Override
    public boolean isDataFrame()
    {
        return false;
    }
}
