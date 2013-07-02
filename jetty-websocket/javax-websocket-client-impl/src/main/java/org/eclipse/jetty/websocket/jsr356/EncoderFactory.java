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

package org.eclipse.jetty.websocket.jsr356;

import java.util.List;

import javax.websocket.Encoder;

/**
 * Represents all of the declared {@link Encoder}s that the Container is aware of.
 */
public class EncoderFactory
{
    public EncoderFactory()
    {
        // TODO Auto-generated constructor stub
    }

    public EncoderFactory(EncoderFactory encoderFactory)
    {
        // TODO Auto-generated constructor stub
    }

    public Encoder getEncoder(Class<?> targetType)
    {
        // TODO Auto-generated method stub
        return null;
    }

    public List<Class<? extends Encoder>> getList()
    {
        // TODO Auto-generated method stub
        return null;
    }

    public void registerAll(Class<? extends Encoder>[] encoders)
    {
        // TODO Auto-generated method stub
    }
}
