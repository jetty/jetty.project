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

package org.eclipse.jetty.http3.qpack.internal.util;

import java.nio.ByteBuffer;

public class NBitIntegerParser
{
    private int _prefix;
    private long _total;
    private long _multiplier;
    private boolean _started;

    public void setPrefix(int prefix)
    {
        if (_started)
            throw new IllegalStateException();
        _prefix = prefix;
    }

    public int decodeInt(ByteBuffer buffer)
    {
        return Math.toIntExact(decodeLong(buffer));
    }

    public long decodeLong(ByteBuffer buffer)
    {
        if (!_started)
        {
            if (!buffer.hasRemaining())
                return -1;

            _started = true;
            _multiplier = 1;
            int nbits = 0xFF >>> (8 - _prefix);
            _total = buffer.get() & nbits;
            if (_total < nbits)
            {
                long total = _total;
                reset();
                return total;
            }
        }

        while (true)
        {
            // If we have no more remaining we return -1 to indicate that more data is needed to continue parsing.
            if (!buffer.hasRemaining())
                return -1;

            int b = buffer.get() & 0xFF;
            _total = Math.addExact(_total, (b & 127) * _multiplier);
            _multiplier = Math.multiplyExact(_multiplier, 128);
            if ((b & 128) == 0)
            {
                long total = _total;
                reset();
                return total;
            }
        }
    }

    public void reset()
    {
        _prefix = 0;
        _total = 0;
        _multiplier = 1;
        _started = false;
    }
}
