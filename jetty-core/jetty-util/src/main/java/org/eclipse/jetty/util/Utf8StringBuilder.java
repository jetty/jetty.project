//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util;

/**
 * <p>UTF-8 StringBuilder.</p>
 * <p>
 * This class wraps a standard {@link StringBuilder} and provides methods to append
 * UTF-8 encoded bytes, that are converted into characters.
 * </p><p>
 * This class is stateful and up to 4 calls to {@link #append(byte)} may be needed before
 * state a character is appended to the string buffer.
 * </p><p>
 * The UTF-8 decoding is done by this class and no additional buffers or Readers are used.
 * </p>
 */
public class Utf8StringBuilder extends Utf8Appendable
{
    final StringBuilder _buffer;

    public Utf8StringBuilder()
    {
        super(new StringBuilder());
        _buffer = (StringBuilder)_appendable;
    }

    public Utf8StringBuilder(int capacity)
    {
        super(new StringBuilder(capacity));
        _buffer = (StringBuilder)_appendable;
    }

    @Override
    public int length()
    {
        return _buffer.length();
    }

    @Override
    protected void resetBuffer()
    {
        _buffer.setLength(0);
    }
}
