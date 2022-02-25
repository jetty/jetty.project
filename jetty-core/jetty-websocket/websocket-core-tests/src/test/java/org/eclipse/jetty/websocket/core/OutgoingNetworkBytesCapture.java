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

package org.eclipse.jetty.websocket.core;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.websocket.core.internal.Generator;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

/**
 * Capture outgoing network bytes.
 */
public class OutgoingNetworkBytesCapture implements OutgoingFrames
{
    private final Generator generator;
    private final List<ByteBuffer> captured;

    public OutgoingNetworkBytesCapture(Generator generator)
    {
        this.generator = generator;
        this.captured = new ArrayList<>();
    }

    public void assertBytes(int idx, String expectedHex)
    {
        assertThat("Capture index does not exist", idx, lessThan(captured.size()));
        ByteBuffer buf = captured.get(idx);
        String actualHex = TypeUtil.toHexString(BufferUtil.toArray(buf)).toUpperCase(Locale.ENGLISH);
        assertThat("captured[" + idx + "]", actualHex, is(expectedHex.toUpperCase(Locale.ENGLISH)));
    }

    public List<ByteBuffer> getCaptured()
    {
        return captured;
    }

    @Override
    public void sendFrame(Frame frame, Callback callback, boolean batch)
    {
        ByteBuffer buf = BufferUtil.allocate(Generator.MAX_HEADER_LENGTH + frame.getPayloadLength());
        generator.generateWholeFrame(frame, buf);
        captured.add(buf);
        if (callback != null)
        {
            callback.succeeded();
        }
    }
}
