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

package org.eclipse.jetty.websocket.common.util;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Utf8Appendable;
import org.eclipse.jetty.util.Utf8StringBuilder;

/**
 * Similar in scope to the {@link Utf8StringBuilder}, but allowing partially constructed Strings without throwing
 * Exceptions for incomplete UTF8 sequences.
 * <p>
 * A call to {@link #toPartialString(ByteBuffer)} will return the section of the String from the start to the last
 * completed UTF8 sequence. Leaving incomplete sequences for a subsequent call to complete.
 */
public class Utf8PartialBuilder
{
    private StringBuilder str;
    private Utf8Appendable utf8;

    public Utf8PartialBuilder()
    {
        reset();
    }

    public String toPartialString(ByteBuffer buf)
    {
        if (buf == null)
        {
            // no change, return empty
            return "";
        }
        utf8.append(buf);
        String ret = str.toString();
        str.setLength(0);
        return ret;
    }
    
    public void reset()
    {
        this.str = new StringBuilder();
        this.utf8 = new Utf8Appendable(str)
        {
            @Override
            public int length()
            {
                return str.length();
            }
        };
    }
}
