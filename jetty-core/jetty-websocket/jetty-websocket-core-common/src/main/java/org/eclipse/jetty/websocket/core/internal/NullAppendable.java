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

package org.eclipse.jetty.websocket.core.internal;

import java.nio.charset.CharacterCodingException;
import java.util.function.Supplier;

import org.eclipse.jetty.util.Utf8StringBuilder;

/**
 * Null Buffer/Builder Appendable.
 * <p>
 *     Used by WebSocket implementation to both validate that incoming bytes conform
 *     to UTF-8 rules, and also to truncated incoming Strings or bytes at
 *     whole codepoints.  (Neither of which require the bytes being checked to be
 *     appended to an internal buffer)
 * </p>
 */
public class NullAppendable extends Utf8StringBuilder
{
    public NullAppendable()
    {
        super(null);
    }

    @Override
    public int length()
    {
        return 0;
    }

    @Override
    public void append(CharSequence chars, int offset, int length)
    {
        checkCharAppend();
    }

    @Override
    public void append(char c)
    {
        checkCharAppend();
    }

    @Override
    public void append(String s)
    {
        checkCharAppend();
    }

    @Override
    public void append(String s, int offset, int length)
    {
        checkCharAppend();
    }

    @Override
    protected void bufferAppend(char c)
    {
    }

    @Override
    protected void bufferReset()
    {
    }

    @Override
    public String toPartialString()
    {
        return null;
    }

    @Override
    public String toCompleteString()
    {
        complete();
        return null;
    }

    @Override
    public <X extends Throwable> String takeCompleteString(Supplier<X> onCodingError) throws X
    {
        complete();
        return takePartialString(onCodingError);
    }

    @Override
    public <X extends Throwable> String takePartialString(Supplier<X> onCodingError) throws X
    {
        if (onCodingError != null && hasCodingErrors())
        {
            X x = onCodingError.get();
            if (x != null)
                throw x;
        }
        return null;
    }

    @Override
    public String build() throws CharacterCodingException
    {
        complete();
        if (hasCodingErrors())
            throw new CharacterCodingException();
        return null;
    }
}
