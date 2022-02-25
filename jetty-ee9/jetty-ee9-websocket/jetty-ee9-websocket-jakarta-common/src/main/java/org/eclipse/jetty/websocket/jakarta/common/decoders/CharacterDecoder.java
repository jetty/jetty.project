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

package org.eclipse.jetty.websocket.jakarta.common.decoders;

import jakarta.websocket.DecodeException;
import jakarta.websocket.Decoder;

/**
 * Default implementation of the {@link jakarta.websocket.Decoder.Text} Message to {@link Character} decoder
 */
public class CharacterDecoder extends AbstractDecoder implements Decoder.Text<Character>
{
    public static final CharacterDecoder INSTANCE = new CharacterDecoder();

    @Override
    public Character decode(String s) throws DecodeException
    {
        return s.charAt(0);
    }

    @Override
    public boolean willDecode(String s)
    {
        if (s == null)
        {
            return false;
        }
        if (s.length() == 1)
        {
            return true;
        }
        // can only parse 1 character
        return false;
    }
}
