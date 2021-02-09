//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.http2.frames;

import org.eclipse.jetty.http.MetaData;

public class PushPromiseFrame extends StreamFrame
{
    private final int promisedStreamId;
    private final MetaData metaData;

    public PushPromiseFrame(int streamId, MetaData metaData)
    {
        this(streamId, 0, metaData);
    }

    public PushPromiseFrame(int streamId, int promisedStreamId, MetaData metaData)
    {
        super(FrameType.PUSH_PROMISE, streamId);
        this.promisedStreamId = promisedStreamId;
        this.metaData = metaData;
    }

    public int getPromisedStreamId()
    {
        return promisedStreamId;
    }

    public MetaData getMetaData()
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
