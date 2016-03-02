//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.decoders;

import javax.websocket.DecodeException;
import javax.websocket.Decoder;


/**
 * Default implementation of the {@link javax.websocket.Decoder.Text} Message to {@link Boolean} decoder.
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
        if (s == null)
        {
            return false;
        }
        return (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"));
    }
}
