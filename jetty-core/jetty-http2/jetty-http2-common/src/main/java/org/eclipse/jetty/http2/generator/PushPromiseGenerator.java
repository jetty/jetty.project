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

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.http2.hpack.HpackException;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;

public class PushPromiseGenerator extends FrameGenerator
{
    private final HpackEncoder encoder;

    public PushPromiseGenerator(HeaderGenerator headerGenerator, HpackEncoder encoder)
    {
        super(headerGenerator);
        this.encoder = encoder;
    }

    @Override
    public int generate(RetainableByteBuffer.Mutable accumulator, Frame frame) throws HpackException
    {
        PushPromiseFrame pushPromiseFrame = (PushPromiseFrame)frame;
        return generatePushPromise(accumulator, pushPromiseFrame.getStreamId(), pushPromiseFrame.getPromisedStreamId(), pushPromiseFrame.getMetaData());
    }

    public int generatePushPromise(RetainableByteBuffer.Mutable accumulator, int streamId, int promisedStreamId, MetaData metaData) throws HpackException
    {
        if (streamId < 0)
            throw new IllegalArgumentException("Invalid stream id: " + streamId);
        if (promisedStreamId < 0)
            throw new IllegalArgumentException("Invalid promised stream id: " + promisedStreamId);

        RetainableByteBuffer hpack = encode(encoder, metaData);
        ByteBuffer hpackByteBuffer = hpack.getByteBuffer();
        BufferUtil.flipToFlush(hpackByteBuffer, 0);
        int hpackLength = hpackByteBuffer.remaining();

        // No support for splitting in CONTINUATION frames,
        // also PushPromiseBodyParser does not support it.

        // The promised streamId length.
        int promisedStreamIdLength = 4;
        int length = hpackLength + promisedStreamIdLength;
        int flags = Flags.END_HEADERS;

        generateHeader(accumulator, FrameType.PUSH_PROMISE, length, flags, streamId);
        accumulator.putInt(promisedStreamId);
        accumulator.add(hpack);

        return Frame.HEADER_LENGTH + length;
    }
}
