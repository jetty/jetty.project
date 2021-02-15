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

package org.eclipse.jetty.websocket.common.message;

import java.nio.ByteBuffer;

// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
public class MessageDebug
{
    public static String toDetailHint(byte[] data, int offset, int len)
    {
        StringBuilder buf = new StringBuilder();
        ByteBuffer buffer = ByteBuffer.wrap(data, offset, len);

        buf.append("byte[").append(data.length);
        buf.append("](o=").append(offset);
        buf.append(",len=").append(len);

        buf.append(")<<<");
        for (int i = buffer.position(); i < buffer.limit(); i++)
        {
            char c = (char)buffer.get(i);
            if ((c >= ' ') && (c <= 127))
            {
                buf.append(c);
            }
            else if ((c == '\r') || (c == '\n'))
            {
                buf.append('|');
            }
            else
            {
                buf.append('\ufffd');
            }
            if ((i == (buffer.position() + 16)) && (buffer.limit() > (buffer.position() + 32)))
            {
                buf.append("...");
                i = buffer.limit() - 16;
            }
        }
        buf.append(">>>");

        return buf.toString();
    }
}
