//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.jsr356.server.samples.beans;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.EndpointConfig;

/**
 * Decode Date
 */
public class DateDecoder implements Decoder.Text<Date>
{
    @Override
    public Date decode(String s) throws DecodeException
    {
        try
        {
            return new SimpleDateFormat("yyyy.MM.dd").parse(s);
        }
        catch (ParseException e)
        {
            throw new DecodeException(s, e.getMessage(), e);
        }
    }

    @Override
    public void destroy()
    {
    }

    @Override
    public void init(EndpointConfig config)
    {
    }

    @Override
    public boolean willDecode(String s)
    {
        return true;
    }
}
