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
 * Default implementation of the {@link javax.websocket.Decoder.Text} Message to {@link Short} decoder
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
            throw new DecodeException(s,"Unable to parse Short",e);
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
