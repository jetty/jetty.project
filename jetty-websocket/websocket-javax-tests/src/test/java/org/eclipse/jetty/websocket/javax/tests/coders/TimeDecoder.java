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

package org.eclipse.jetty.websocket.javax.tests.coders;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.EndpointConfig;

/**
 * Decode Time
 */
public class TimeDecoder implements Decoder.Text<Date>
{
    @Override
    public Date decode(String s) throws DecodeException
    {
        try
        {
            return new SimpleDateFormat("HH:mm:ss z").parse(s);
        }
        catch (ParseException e)
        {
            throw new DecodeException(s, e.getMessage(), e);
        }
    }

    @Override
    public void destroy()
    {
        CoderEventTracking.getInstance().addEvent(this, "destroy()");
    }

    @Override
    public void init(EndpointConfig config)
    {
        CoderEventTracking.getInstance().addEvent(this, "init(EndpointConfig)");
    }

    @Override
    public boolean willDecode(String s)
    {
        CoderEventTracking.getInstance().addEvent(this, "willDecode()");
        return true;
    }
}
