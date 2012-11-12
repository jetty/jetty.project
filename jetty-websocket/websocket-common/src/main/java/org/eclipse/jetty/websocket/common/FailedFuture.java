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

package org.eclipse.jetty.websocket.common;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import javax.net.websocket.SendHandler;
import javax.net.websocket.SendResult;

public class FailedFuture extends FutureTask<SendResult> implements Future<SendResult>
{
    private static class FailedRunner implements Callable<SendResult>
    {
        private SendHandler completion;
        private Throwable error;

        public FailedRunner(SendHandler completion, Throwable error)
        {
            this.completion = completion;
            this.error = error;
        }

        @Override
        public SendResult call() throws Exception
        {
            SendResult result = new SendResult(this.error);
            if (completion != null)
            {
                completion.setResult(result);
            }
            return result;
        }
    }

    public FailedFuture(SendHandler completion, Throwable error)
    {
        super(new FailedRunner(completion,error));
        run();
    }
}
