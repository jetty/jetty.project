//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests;

import java.nio.ByteBuffer;
import java.util.Arrays;

public final class DataUtils
{
    /**
     * Make a copy of a byte buffer.
     * <p>
     * This is important in some tests, as the underlying byte buffer contained in a Frame can be modified through
     * masking and make it difficult to compare the results in the fuzzer.
     *
     * @param payload the payload to copy
     * @return a new byte array of the payload contents
     */
    public static ByteBuffer copyOf(ByteBuffer payload)
    {
        ByteBuffer copy = ByteBuffer.allocate(payload.remaining());
        copy.put(payload.slice());
        copy.flip();
        return copy;
    }
    
    /**
     * Make a copy of a byte buffer.
     * <p>
     * This is important in some tests, as the underlying byte buffer contained in a Frame can be modified through
     * masking and make it difficult to compare the results in the fuzzer.
     *
     * @param payload the payload to copy
     * @return a new byte array of the payload contents
     */
    public static ByteBuffer copyOf(byte[] payload)
    {
        return ByteBuffer.wrap(Arrays.copyOf(payload, payload.length));
    }
}
