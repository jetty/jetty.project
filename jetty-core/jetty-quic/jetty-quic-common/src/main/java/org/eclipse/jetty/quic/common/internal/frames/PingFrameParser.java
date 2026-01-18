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

package org.eclipse.jetty.quic.common.internal.frames;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.PingFrame;
import org.eclipse.jetty.quic.util.VarLenInt;

/// A parser for QUIC PING frames.
public class PingFrameParser implements FrameParser
{
    private static final PingFrame PING_FRAME = new PingFrame();

    @Override
    public Frame parse(RetainableByteBuffer buffer)
    {
        VarLenInt.decodeLong(buffer.getByteBuffer());
        return PING_FRAME;
    }
}
