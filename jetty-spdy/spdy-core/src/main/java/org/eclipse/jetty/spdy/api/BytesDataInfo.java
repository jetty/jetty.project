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

package org.eclipse.jetty.spdy.api;

import java.nio.ByteBuffer;

/**
 * <p>Specialized {@link DataInfo} for byte array content.</p>
 */
public class BytesDataInfo extends DataInfo
{
    private final byte[] bytes;
    private final int offset;
    private final int length;
    private int index;

    public BytesDataInfo(byte[] bytes, boolean close)
    {
        this(bytes, 0, bytes.length, close);
    }

    public BytesDataInfo(byte[] bytes, int offset, int length, boolean close)
    {
        super(close, false);
        this.bytes = bytes;
        this.offset = offset;
        this.length = length;
        this.index = offset;
    }

    @Override
    public int length()
    {
        return length;
    }

    @Override
    public int available()
    {
        return length - index + offset;
    }

    @Override
    public int readInto(ByteBuffer output)
    {
        int space = output.remaining();
        int chunk = Math.min(available(), space);
        output.put(bytes, index, chunk);
        index += chunk;
        return chunk;
    }
}
