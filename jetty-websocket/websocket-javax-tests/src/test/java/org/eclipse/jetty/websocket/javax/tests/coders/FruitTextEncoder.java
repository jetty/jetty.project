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

import javax.websocket.EncodeException;
import javax.websocket.Encoder;
import javax.websocket.EndpointConfig;

public class FruitTextEncoder implements Encoder.Text<Fruit>
{
    @Override
    public void destroy()
    {
    }

    @Override
    public String encode(Fruit fruit) throws EncodeException
    {
        return String.format("%s|%s", fruit.name, fruit.color);
    }

    @Override
    public void init(EndpointConfig config)
    {
    }
}
