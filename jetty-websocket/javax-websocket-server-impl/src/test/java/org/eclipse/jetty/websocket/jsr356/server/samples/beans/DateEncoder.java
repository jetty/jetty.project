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

import java.text.SimpleDateFormat;
import java.util.Date;
import javax.websocket.EncodeException;
import javax.websocket.Encoder;
import javax.websocket.EndpointConfig;

/**
 * Encode Date
 */
public class DateEncoder implements Encoder.Text<Date>
{
    @Override
    public void destroy()
    {
    }

    @Override
    public String encode(Date object) throws EncodeException
    {
        return new SimpleDateFormat("yyyy.MM.dd").format(object);
    }

    @Override
    public void init(EndpointConfig config)
    {
    }
}
