//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.tests;

import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;

/**
 * Allow Fuzzer / Generator to create bad frames for testing frame validation
 */
public class BadFrame extends Frame
{
    public BadFrame(byte opcode)
    {
        super(OpCode.CONTINUATION);
        super.finRsvOp = (byte)((finRsvOp & 0xF0) | (opcode & 0x0F));
        // NOTE: Not setting Frame.Type intentionally
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
