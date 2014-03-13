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

package org.eclipse.jetty.websocket.common;

import java.io.IOException;

import org.eclipse.jetty.util.SharedBlockingCallback;
import org.eclipse.jetty.websocket.api.WriteCallback;


/* ------------------------------------------------------------ */
/** extend a SharedlBlockingCallback to an websocket WriteCallback
 */
public class BlockingWriteCallback extends SharedBlockingCallback
{
    public BlockingWriteCallback()
    {
        super(new WriteBlocker());
    }
        
    public WriteBlocker acquireWriteBlocker() throws IOException
    {
        return (WriteBlocker)acquire();
    }
    
    public static class WriteBlocker extends Blocker implements WriteCallback
    {
        @Override
        public void writeFailed(Throwable x)
        {
            failed(x);
        }

        @Override
        public void writeSuccess()
        {
            succeeded();
        }
    }
}
