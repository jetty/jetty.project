//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.FrameType;
import org.eclipse.jetty.io.ByteBufferPool;

public class Generator
{
    private final FrameGenerator[] generators = new FrameGenerator[FrameType.maxType() + 1];

    public Generator()
    {
        generators[FrameType.DATA.type()] = new DataGenerator();
        generators[FrameType.HEADERS.type()] = new HeadersGenerator();
        generators[FrameType.CANCEL_PUSH.type()] = new CancelPushGenerator();
        generators[FrameType.SETTINGS.type()] = new SettingsGenerator();
        generators[FrameType.PUSH_PROMISE.type()] = new PushPromiseGenerator();
        generators[FrameType.GOAWAY.type()] = new GoAwayGenerator();
        generators[FrameType.MAX_PUSH_ID.type()] = new MaxPushIdGenerator();
    }

    public int generate(ByteBufferPool.Lease lease, Frame frame)
    {
        return generators[frame.getFrameType().type()].generate(lease, frame);
    }
}
