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

package org.eclipse.jetty.tests.webapp.websocket;

import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.EndpointConfig;

public class StringSequenceDecoder implements Decoder.Text<StringSequence>
{
    @Override
    public StringSequence decode(String s) throws DecodeException
    {
        return new StringSequence(s);
    }

    @Override
    public void init(EndpointConfig config)
    {

    }

    @Override
    public void destroy()
    {

    }

    @Override
    public boolean willDecode(String s)
    {
        return true;
    }
}
