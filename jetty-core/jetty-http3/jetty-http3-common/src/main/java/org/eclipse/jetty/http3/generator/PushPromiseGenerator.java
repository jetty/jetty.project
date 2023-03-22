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

package org.eclipse.jetty.http3.generator;

import java.util.function.Consumer;

import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.io.ByteBufferPool;

public class PushPromiseGenerator extends FrameGenerator
{
    public PushPromiseGenerator(ByteBufferPool bufferPool)
    {
        super(bufferPool);
    }

    @Override
    public int generate(ByteBufferPool.Accumulator accumulator, long streamId, Frame frame, Consumer<Throwable> fail)
    {
        return 0;
    }
}
