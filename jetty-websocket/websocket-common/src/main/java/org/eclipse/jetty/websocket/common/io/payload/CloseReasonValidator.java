//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.io.payload;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.common.OpCode;

/**
 * Validate UTF8 correctness for {@link OpCode#CLOSE} Reason message.
 */
public class CloseReasonValidator extends UTF8Validator implements PayloadProcessor
{
    private int statusCodeBytes = 2;

    @Override
    public void process(ByteBuffer payload)
    {
        if ((payload == null) || (payload.remaining() <= 2))
        {
            // no validation needed
            return;
        }

        ByteBuffer copy = payload.slice();
        while (statusCodeBytes > 0)
        {
            copy.get();
            statusCodeBytes--;
        }

        super.process(copy);
    }
}
