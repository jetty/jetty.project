//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client.util;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Iterator;

import org.eclipse.jetty.client.api.ContentProvider;

public class ByteBufferContentProvider implements ContentProvider
{
    private final ByteBuffer[] buffers;

    public ByteBufferContentProvider(ByteBuffer... buffers)
    {
        this.buffers = buffers;
    }

    @Override
    public long length()
    {
        int length = 0;
        for (ByteBuffer buffer : buffers)
            length += buffer.remaining();
        return length;
    }

    @Override
    public Iterator<ByteBuffer> iterator()
    {
        return Arrays.asList(buffers).iterator();
    }
}
