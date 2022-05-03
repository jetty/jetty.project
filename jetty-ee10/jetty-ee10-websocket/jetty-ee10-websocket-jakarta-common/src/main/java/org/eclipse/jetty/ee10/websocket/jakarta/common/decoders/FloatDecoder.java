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

package org.eclipse.jetty.ee10.websocket.jakarta.common.decoders;

import jakarta.websocket.DecodeException;
import jakarta.websocket.Decoder;

/**
 * Default implementation of the Text Message to {@link Float} decoder
 */
public class FloatDecoder extends AbstractDecoder implements Decoder.Text<Float>
{
    public static final FloatDecoder INSTANCE = new FloatDecoder();

    @Override
    public Float decode(String s) throws DecodeException
    {
        try
        {
            Float val = Float.parseFloat(s);
            if (val.isNaN())
            {
                throw new DecodeException(s, "NaN");
            }
            return val;
        }
        catch (NumberFormatException e)
        {
            throw new DecodeException(s, "Unable to parse float", e);
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
            Float val = Float.parseFloat(s);
            return (!val.isNaN());
        }
        catch (NumberFormatException e)
        {
            return false;
        }
    }
}
