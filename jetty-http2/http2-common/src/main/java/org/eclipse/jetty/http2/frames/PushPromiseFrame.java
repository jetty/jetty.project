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

package org.eclipse.jetty.http2.frames;

import org.eclipse.jetty.http.MetaData;

public class PushPromiseFrame extends StreamFrame
{
    private final int promisedStreamId;
    private final MetaData.Request metaData;

    public PushPromiseFrame(int streamId, MetaData.Request metaData)
    {
        this(streamId, 0, metaData);
    }

    public PushPromiseFrame(int streamId, int promisedStreamId, MetaData.Request metaData)
    {
        super(FrameType.PUSH_PROMISE, streamId);
        this.promisedStreamId = promisedStreamId;
        this.metaData = metaData;
    }

    public int getPromisedStreamId()
    {
        return promisedStreamId;
    }

    public MetaData.Request getMetaData()
    {
        return metaData;
    }

    @Override
    public PushPromiseFrame withStreamId(int streamId)
    {
        return new PushPromiseFrame(getStreamId(), streamId, getMetaData());
    }

    @Override
    public String toString()
    {
        return String.format("%s#%d/#%d", super.toString(), getStreamId(), promisedStreamId);
    }
}
