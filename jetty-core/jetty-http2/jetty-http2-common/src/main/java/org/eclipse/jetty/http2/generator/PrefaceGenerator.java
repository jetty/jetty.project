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

package org.eclipse.jetty.http2.generator;

import java.nio.ByteBuffer;
import java.util.List;

import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.PrefaceFrame;
import org.eclipse.jetty.util.buffer.ReadableBuffer;

public class PrefaceGenerator extends FrameGenerator
{
    private static final ReadableBuffer PREFACE = ReadableBuffer.wrap(ByteBuffer.wrap(PrefaceFrame.PREFACE_BYTES));

    public PrefaceGenerator()
    {
        super(null);
    }

    @Override
    public int generate(List<ReadableBuffer> accumulator, Frame frame)
    {
        ReadableBuffer slice = PREFACE.slice();
        accumulator.add(slice);
        return PrefaceFrame.PREFACE_BYTES.length;
    }
}
