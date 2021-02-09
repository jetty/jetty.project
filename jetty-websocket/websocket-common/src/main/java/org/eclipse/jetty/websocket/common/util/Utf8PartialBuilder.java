//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.common.util;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Utf8StringBuilder;

/**
 * Similar in scope to the {@link Utf8StringBuilder}, but allowing partially constructed Strings without throwing
 * Exceptions for incomplete UTF8 sequences.
 * <p>
 * A call to {@link #toPartialString(ByteBuffer)} will return the section of the String from the start to the last
 * completed UTF8 sequence. Leaving incomplete sequences for a subsequent call to complete.
 */
@Deprecated
public class Utf8PartialBuilder
{
    private final Utf8StringBuilder utf8 = new Utf8StringBuilder();

    public Utf8PartialBuilder()
    {
    }

    public String toPartialString(ByteBuffer buf)
    {
        if (buf == null)
        {
            // no change, return empty
            return "";
        }
        utf8.append(buf);
        return utf8.takePartialString();
    }
}
