//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.tests.coders;

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
