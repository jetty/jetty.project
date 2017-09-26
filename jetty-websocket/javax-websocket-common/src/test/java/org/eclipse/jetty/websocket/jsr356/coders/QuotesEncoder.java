//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import javax.websocket.EncodeException;
import javax.websocket.Encoder;
import javax.websocket.EndpointConfig;

public class QuotesEncoder implements Encoder.Text<Quotes>
{
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
        CoderEventTracking.getInstance().addEvent(this, "destroy()");
    }
    
    @Override
    public void init(EndpointConfig config)
    {
        CoderEventTracking.getInstance().addEvent(this, "init(EndpointConfig)");
    }
}
