//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.message;

import java.nio.ByteBuffer;
import java.util.function.Function;

import org.eclipse.jetty.util.BufferUtil;

/**
 * {@link Function} argument for Partial Binary Messages
 */
public class PartialBinaryMessage
{
    private final ByteBuffer payload;
    private final boolean fin;
    
    public PartialBinaryMessage(ByteBuffer payload, boolean fin)
    {
        this.payload = payload == null ? BufferUtil.EMPTY_BUFFER : payload;
        this.fin = fin;
    }
    
    public ByteBuffer getPayload()
    {
        return payload;
    }
    
    public boolean isFin()
    {
        return fin;
    }
}
