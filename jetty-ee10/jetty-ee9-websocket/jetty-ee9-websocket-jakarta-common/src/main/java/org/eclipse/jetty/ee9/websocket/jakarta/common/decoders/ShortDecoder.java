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

package org.eclipse.jetty.ee9.websocket.jakarta.common.decoders;

import jakarta.websocket.DecodeException;
import jakarta.websocket.Decoder;

/**
 * Default implementation of the {@link jakarta.websocket.Decoder.Text} Message to {@link Short} decoder
 */
public class ShortDecoder extends AbstractDecoder implements Decoder.Text<Short>
{
    public static final ShortDecoder INSTANCE = new ShortDecoder();

    @Override
    public Short decode(String s) throws DecodeException
    {
        try
        {
            return Short.parseShort(s);
        }
        catch (NumberFormatException e)
        {
            throw new DecodeException(s, "Unable to parse Short", e);
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
            Short.parseShort(s);
            return true;
        }
        catch (NumberFormatException e)
        {
            return false;
        }
    }
}
