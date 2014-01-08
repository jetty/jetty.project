//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.mux;

import org.eclipse.jetty.websocket.mux.op.MuxDropChannel;

public class MuxPhysicalConnectionException extends MuxException
{
    private static final long serialVersionUID = 1L;
    private MuxDropChannel drop;

    public MuxPhysicalConnectionException(MuxDropChannel.Reason code, String phrase)
    {
        super(phrase);
        drop = new MuxDropChannel(0,code,phrase);
    }

    public MuxPhysicalConnectionException(MuxDropChannel.Reason code, String phrase, Throwable t)
    {
        super(phrase,t);
        drop = new MuxDropChannel(0,code,phrase);
    }

    public MuxDropChannel getMuxDropChannel()
    {
        return drop;
    }
}
