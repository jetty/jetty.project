//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;

import java.util.concurrent.atomic.AtomicBoolean;

public abstract class IteratingCallback implements Callback
{
    final AtomicBoolean _iterating = new AtomicBoolean();
    final Callback _callback;
    
    
    public IteratingCallback(Callback callback)
    {
        _callback=callback;
    }
    
    abstract protected boolean process() throws Exception;
    
    public void iterate()
    {
        try
        {
            // Keep iterating as long as succeeded() is called during process()
            while(_iterating.compareAndSet(false,true))
            {
                // process and test if we are complete
                if (process())
                {
                    _callback.succeeded();
                    return;
                }
            }
        }
        catch(Exception e)
        {
            _iterating.set(false);
            _callback.failed(e);
        }
        finally
        {
            _iterating.set(false);
        }
    }
    
    
    @Override
    public void succeeded()
    {
        if (!_iterating.compareAndSet(true,false))
            iterate();
    }

    @Override
    public void failed(Throwable x)
    {
        _callback.failed(x);
    }

}
