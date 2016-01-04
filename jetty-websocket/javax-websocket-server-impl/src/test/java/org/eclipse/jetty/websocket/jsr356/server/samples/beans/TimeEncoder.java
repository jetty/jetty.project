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

package org.eclipse.jetty.websocket.jsr356.server.samples.beans;

import java.text.SimpleDateFormat;
import java.util.Date;

import javax.websocket.EncodeException;
import javax.websocket.Encoder;
import javax.websocket.EndpointConfig;

/**
 * Encode Time
 */
public class TimeEncoder implements Encoder.Text<Date>
{
    @Override
    public void destroy()
    {
    }

    @Override
    public String encode(Date object) throws EncodeException
    {
        return new SimpleDateFormat("HH:mm:ss z").format(object);
    }

    @Override
    public void init(EndpointConfig config)
    {
    }
}
