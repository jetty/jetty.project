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

package org.eclipse.jetty.ee.websocket.jakarta.common.decoders;

import jakarta.websocket.DecodeException;
import jakarta.websocket.Decoder;
import org.eclipse.jetty.util.StringUtil;

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
        // Behavior of WebSocket TCK (it will accept any positive length string, as long as it is not all whitespace)
        return !StringUtil.isEmpty(s);
    }
}
