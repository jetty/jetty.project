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
 * Default implementation of the {@link javax.websocket.Decoder.Text} Message to {@link String} decoder
 */
public class StringDecoder extends AbstractDecoder implements Decoder.Text<String>
{
    public static final StringDecoder INSTANCE = new StringDecoder();

    @Override
    public String decode(String s) throws DecodeException
    {
        return s;
    }

    @Override
    public boolean willDecode(String s)
    {
        return true;
    }
}
