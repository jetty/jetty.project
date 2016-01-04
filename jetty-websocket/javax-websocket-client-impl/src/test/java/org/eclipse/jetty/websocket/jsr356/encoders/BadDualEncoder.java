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

package org.eclipse.jetty.websocket.jsr356.encoders;

import java.io.IOException;
import java.io.Writer;

import javax.websocket.EncodeException;
import javax.websocket.Encoder;
import javax.websocket.EndpointConfig;

/**
 * Intentionally bad example of attempting to encode the same object for different message types.
 */
public class BadDualEncoder implements Encoder.Text<Integer>, Encoder.TextStream<Integer>
{
    @Override
    public void destroy()
    {
    }

    @Override
    public String encode(Integer object) throws EncodeException
    {
        return Integer.toString(object);
    }

    @Override
    public void encode(Integer object, Writer writer) throws EncodeException, IOException
    {
        writer.write(Integer.toString(object));
    }

    @Override
    public void init(EndpointConfig config)
    {
    }
}
