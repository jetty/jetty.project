//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.websocket.jakarta.tests;

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
        if (payload == null)
            return null;

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
