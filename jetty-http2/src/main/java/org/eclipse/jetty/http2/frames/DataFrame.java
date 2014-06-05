//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.nio.ByteBuffer;

public class DataFrame
{
    public static final int MAX_LENGTH = 0x3F_FF;

    private final int streamId;
    private final ByteBuffer data;
    private boolean end;

    public DataFrame(int streamId, ByteBuffer data, boolean end)
    {
        this.streamId = streamId;
        this.data = data;
        this.end = end;
    }

    public int getStreamId()
    {
        return streamId;
    }

    public boolean isEnd()
    {
        return end;
    }

    public ByteBuffer getData()
    {
        return data;
    }
}
