//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.decoders.samples;

import javax.websocket.DecodeException;

public class SecondDecoder implements ExtDecoder<Integer>
{
    private String id;

    @Override
    public Integer decode(String s) throws DecodeException
    {
        try
        {
            return Integer.parseInt(s);
        }
        catch (NumberFormatException e)
        {
            throw new DecodeException(s,"Unable to parse Integer",e);
        }
    }

    @Override
    public void setId(String id)
    {
        this.id = id;
    }

    @Override
    public String toString()
    {
        return "SecondDecoder[id=" + id + "]";
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
            Integer.parseInt(s);
            return true;
        }
        catch (NumberFormatException e)
        {
            return false;
        }
    }
}
