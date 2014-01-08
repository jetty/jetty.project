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

package org.eclipse.jetty.server;

import java.nio.ByteBuffer;

/**
 * <p>An implementation of HttpInput using {@link ByteBuffer} as items.</p>
 */
public class ByteBufferQueuedHttpInput extends QueuedHttpInput<ByteBuffer>
{
    @Override
    protected int remaining(ByteBuffer item)
    {
        return item.remaining();
    }

    @Override
    protected int get(ByteBuffer item, byte[] buffer, int offset, int length)
    {
        int l = Math.min(item.remaining(), length);
        item.get(buffer, offset, l);
        return l;
    }
    
    @Override
    protected void consume(ByteBuffer item, int length)
    {
        item.position(item.position()+length);
    }

    @Override
    protected void onContentConsumed(ByteBuffer item)
    {
    }

}
