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

package org.eclipse.jetty.websocket.common.message;

import java.util.function.Function;

/**
 * {@link Function} argument for Partial Text Messages
 */
public class PartialTextMessage
{
    private final String payload;
    private final boolean fin;
    
    public PartialTextMessage(String payload, boolean fin)
    {
        this.payload = payload;
        this.fin = fin;
    }

    public String getPayload()
    {
        return payload;
    }

    public boolean isFin()
    {
        return fin;
    }
}
