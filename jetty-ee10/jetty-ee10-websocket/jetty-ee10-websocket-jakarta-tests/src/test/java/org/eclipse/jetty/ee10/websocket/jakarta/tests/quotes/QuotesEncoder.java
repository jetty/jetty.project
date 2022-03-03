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

package org.eclipse.jetty.ee9.websocket.jakarta.tests.quotes;

import jakarta.websocket.EncodeException;
import jakarta.websocket.Encoder;
import jakarta.websocket.EndpointConfig;

public class QuotesEncoder implements Encoder.Text<Quotes>
{
    @SuppressWarnings("RedundantThrows")
    @Override
    public String encode(Quotes q) throws EncodeException
    {
        StringBuilder buf = new StringBuilder();
        buf.append("Author: ").append(q.getAuthor()).append('\n');
        for (String quote : q.getQuotes())
        {
            buf.append("Quote: ").append(quote).append('\n');
        }
        return buf.toString();
    }

    @Override
    public void destroy()
    {
    }

    @Override
    public void init(EndpointConfig config)
    {
    }
}
