//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

public class SendFailure
{
    public final Throwable failure;
    public final boolean retry;

    public SendFailure(Throwable failure, boolean retry)
    {
        this.failure = failure;
        this.retry = retry;
    }

    @Override
    public String toString()
    {
        return String.format("%s[failure=%s,retry=%b]", super.toString(), failure, retry);
    }
}
