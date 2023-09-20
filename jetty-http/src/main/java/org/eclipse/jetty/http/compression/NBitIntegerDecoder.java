//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.http.compression;

import java.nio.ByteBuffer;

/**
 * Used to decode integers as described in RFC7541.
 */
public class NBitIntegerDecoder
{
    private int _prefix;
    private long _total;
    private long _multiplier;
    private boolean _started;

    /**
     * Set the prefix length in of the integer representation in bits.
     * A prefix of 6 means the integer representation starts after the first 2 bits.
     * @param prefix the number of bits in the integer prefix.
     */
    public void setPrefix(int prefix)
    {
        if (_started)
            throw new IllegalStateException();
        _prefix = prefix;
    }

    /**
     * Decode an integer from the buffer. If the buffer does not contain the complete integer representation
     * a value of -1 is returned to indicate that more data is needed to complete parsing.
     * This should be only after the prefix has been set with {@link #setPrefix(int)}.
     * @param buffer the buffer containing the encoded integer.
     * @return the decoded integer or -1 to indicate that more data is needed.
     * @throws ArithmeticException if the value overflows a int.
     */
    public int decodeInt(ByteBuffer buffer)
    {
        return Math.toIntExact(decodeLong(buffer));
    }

    /**
     * Decode a long from the buffer. If the buffer does not contain the complete integer representation
     * a value of -1 is returned to indicate that more data is needed to complete parsing.
     * This should be only after the prefix has been set with {@link #setPrefix(int)}.
     * @param buffer the buffer containing the encoded integer.
     * @return the decoded long or -1 to indicate that more data is needed.
     * @throws ArithmeticException if the value overflows a long.
     */
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

    /**
     * Reset the internal state of the parser.
     */
    public void reset()
    {
        _prefix = 0;
        _total = 0;
        _multiplier = 1;
        _started = false;
    }
}
