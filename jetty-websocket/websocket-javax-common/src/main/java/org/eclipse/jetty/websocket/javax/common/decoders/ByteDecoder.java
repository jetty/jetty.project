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

package org.eclipse.jetty.websocket.javax.common.decoders;

import javax.websocket.DecodeException;
import javax.websocket.Decoder;

/**
 * Default implementation of the {@link javax.websocket.Decoder.Text} Message to {@link Byte} decoder
 */
public class ByteDecoder extends AbstractDecoder implements Decoder.Text<Byte>
{
    public static final ByteDecoder INSTANCE = new ByteDecoder();

    @Override
    public Byte decode(String s) throws DecodeException
    {
        try
        {
            return Byte.parseByte(s);
        }
        catch (NumberFormatException e)
        {
            throw new DecodeException(s, "Unable to parse Byte", e);
        }
    }

    @Override
    public boolean willDecode(String s)
    {
        if (s == null)
        {
            return false;
        }
        try
        {
            Byte.parseByte(s);
            return true;
        }
        catch (NumberFormatException e)
        {
            return false;
        }
    }
}
