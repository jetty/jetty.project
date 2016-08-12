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

package org.eclipse.jetty.load.generator;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;

/**
 *
 */
public class LoadGeneratorResultHandler
    implements Response.CompleteListener
{

    private final LoadGeneratorResult loadGeneratorResult;

    public LoadGeneratorResultHandler( LoadGeneratorResult loadGeneratorResult )
    {
        this.loadGeneratorResult = loadGeneratorResult;
    }

    @Override
    public void onComplete( Result result )
    {
        this.loadGeneratorResult.getTotalResponse().incrementAndGet();

        if ( result.isSucceeded() )
        {
            this.loadGeneratorResult.getTotalSuccess().incrementAndGet();
        }
        else
        {
            this.loadGeneratorResult.getTotalFailure().incrementAndGet();
        }
    }

    public LoadGeneratorResult getLoadGeneratorResult()
    {
        return loadGeneratorResult;
    }
}
