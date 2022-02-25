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

package org.eclipse.jetty.http3.internal.parser;

import java.nio.ByteBuffer;

public class UnknownBodyParser extends BodyParser
{
    private long length = -1;

    public UnknownBodyParser(HeaderParser headerParser, ParserListener listener)
    {
        super(headerParser, listener);
    }

    @Override
    public Result parse(ByteBuffer buffer)
    {
        if (length < 0)
            length = getBodyLength();
        int remaining = buffer.remaining();
        if (remaining >= length)
        {
            buffer.position(buffer.position() + (int)length);
            length = -1;
            return Result.WHOLE_FRAME;
        }
        else
        {
            buffer.position(buffer.limit());
            length -= remaining;
            return Result.NO_FRAME;
        }
    }
}
