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

package org.eclipse.jetty.http3.internal.generator;

import java.util.function.Consumer;

import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.FrameType;
import org.eclipse.jetty.io.ByteBufferPool;

public class ControlGenerator
{
    private final FrameGenerator[] generators = new FrameGenerator[FrameType.maxType() + 1];

    public ControlGenerator(boolean useDirectByteBuffers)
    {
        generators[FrameType.CANCEL_PUSH.type()] = new CancelPushGenerator();
        generators[FrameType.SETTINGS.type()] = new SettingsGenerator(useDirectByteBuffers);
        generators[FrameType.GOAWAY.type()] = new GoAwayGenerator(useDirectByteBuffers);
        generators[FrameType.MAX_PUSH_ID.type()] = new MaxPushIdGenerator();
    }

    public int generate(ByteBufferPool.Lease lease, long streamId, Frame frame, Consumer<Throwable> fail)
    {
        return generators[frame.getFrameType().type()].generate(lease, streamId, frame, fail);
    }
}
