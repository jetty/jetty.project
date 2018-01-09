//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.coders;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.EndpointConfig;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class QuotesDecoder implements Decoder.TextStream<Quotes>
{
    private static final Logger LOG = Log.getLogger(QuotesDecoder.class);
    
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
