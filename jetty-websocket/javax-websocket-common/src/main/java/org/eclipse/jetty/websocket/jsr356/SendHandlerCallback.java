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

package org.eclipse.jetty.websocket.jsr356;

import javax.websocket.SendHandler;
import javax.websocket.SendResult;

import org.eclipse.jetty.util.Callback;

public class SendHandlerCallback implements Callback
{
    private final SendHandler sendHandler;

    public SendHandlerCallback(SendHandler sendHandler)
    {
        this.sendHandler = sendHandler;
    }

    @Override
    public void failed(Throwable x)
    {
        sendHandler.onResult(new SendResult(x));
    }

    @Override
    public void succeeded()
    {
        sendHandler.onResult(new SendResult());
    }
}
