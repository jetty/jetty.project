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

package org.eclipse.jetty.ee10.websocket.jakarta.tests.coders;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

import jakarta.websocket.DecodeException;
import jakarta.websocket.Decoder;
import jakarta.websocket.EndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuotesDecoder implements Decoder.TextStream<Quotes>
{
    private static final Logger LOG = LoggerFactory.getLogger(QuotesDecoder.class);

    @Override
    public Quotes decode(Reader reader) throws DecodeException, IOException
    {
        Quotes quotes = new Quotes();
        try (BufferedReader buf = new BufferedReader(reader))
        {
            LOG.debug("decode() begin");
            String line;
            while ((line = buf.readLine()) != null)
            {
                LOG.debug("decode() line = {}", line);
                switch (line.charAt(0))
                {
                    case 'a':
                        quotes.setAuthor(line.substring(2));
                        break;
                    case 'q':
                        quotes.addQuote(line.substring(2));
                        break;
                }
            }
            LOG.debug("decode() complete");
        }
        return quotes;
    }

    @Override
    public void destroy()
    {
        CoderEventTracking.getInstance().addEvent(this, "destroy()");
    }

    @Override
    public void init(EndpointConfig config)
    {
        CoderEventTracking.getInstance().addEvent(this, "init(EndpointConfig)");
    }
}
