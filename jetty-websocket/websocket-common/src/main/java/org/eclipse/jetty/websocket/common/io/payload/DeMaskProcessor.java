//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.io.payload;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.extensions.Frame;

public class DeMaskProcessor implements PayloadProcessor
{
    private boolean isMasked;
    private byte mask[];
    private int offset;

    @Override
    public void process(ByteBuffer payload)
    {
        if (!isMasked)
        {
            return;
        }

        int start = payload.position();
        int end = payload.limit();
        for (int i = start; i < end; i++, offset++)
        {
            payload.put(i,(byte)(payload.get(i) ^ mask[offset % 4]));
        }
    }

    @Override
    public void reset(Frame frame)
    {
        this.isMasked = frame.isMasked();
        if (isMasked)
        {
            this.mask = frame.getMask();
        }
        else
        {
            this.mask = null;
        }

        offset = 0;
    }
}
