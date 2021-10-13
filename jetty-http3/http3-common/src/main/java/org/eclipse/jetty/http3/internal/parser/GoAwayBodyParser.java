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

package org.eclipse.jetty.http3.internal.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http3.frames.GoAwayFrame;
import org.eclipse.jetty.http3.internal.VarLenInt;

public class GoAwayBodyParser extends BodyParser
{
    private final VarLenInt varLenInt = new VarLenInt();

    public GoAwayBodyParser(HeaderParser headerParser, ParserListener listener)
    {
        super(headerParser, listener);
    }

    @Override
    public Result parse(ByteBuffer buffer)
    {
        if (varLenInt.decode(buffer, this::onGoAway))
            return Result.WHOLE_FRAME;
        return Result.NO_FRAME;
    }

    private void onGoAway(long id)
    {
        GoAwayFrame frame = new GoAwayFrame(id);
        notifyGoAway(frame);
    }
}
