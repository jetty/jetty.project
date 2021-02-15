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

package org.eclipse.jetty.websocket.jsr356.samples;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.websocket.DecodeException;
import javax.websocket.EndpointConfig;

public class FruitDecoder implements ExtDecoder<Fruit>
{
    private String id;

    @Override
    public Fruit decode(String s) throws DecodeException
    {
        Pattern pat = Pattern.compile("([^|]*)|([^|]*)");
        Matcher mat = pat.matcher(s);
        if (!mat.find())
        {
            throw new DecodeException(s, "Unable to find Fruit reference encoded in text message");
        }

        Fruit fruit = new Fruit();
        fruit.name = mat.group(1);
        fruit.color = mat.group(2);

        return fruit;
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

        Pattern pat = Pattern.compile("([^|]*)|([^|]*)");
        Matcher mat = pat.matcher(s);
        return (mat.find());
    }
}
