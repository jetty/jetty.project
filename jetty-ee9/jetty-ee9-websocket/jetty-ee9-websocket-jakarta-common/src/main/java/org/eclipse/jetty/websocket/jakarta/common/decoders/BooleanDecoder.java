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
 * Default implementation of the {@link jakarta.websocket.Decoder.Text} Message to {@link Boolean} decoder.
 * <p>
 * Note: delegates to {@link Boolean#parseBoolean(String)} and will only support "true" and "false" as boolean values.
 */
public class BooleanDecoder extends AbstractDecoder implements Decoder.Text<Boolean>
{
    public static final BooleanDecoder INSTANCE = new BooleanDecoder();

    @Override
    public Boolean decode(String s) throws DecodeException
    {
        return Boolean.parseBoolean(s);
    }

    @Override
    public boolean willDecode(String s)
    {
        return (s != null);
    }
}
