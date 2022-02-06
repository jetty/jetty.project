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

package org.eclipse.jetty.websocket.javax.common.coders.tests;

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
