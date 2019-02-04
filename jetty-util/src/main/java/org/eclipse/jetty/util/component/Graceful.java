//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.component;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.FutureCallback;

/* ------------------------------------------------------------ */
/* A Lifecycle that can be gracefully shutdown.
 */
public interface Graceful
{
    public Future<Void> shutdown();

    public boolean isShutdown();
    
    
    public static class Shutdown implements Graceful
    {
        private final AtomicReference<FutureCallback> _shutdown=new AtomicReference<>();
        
        protected FutureCallback newShutdownCallback()
        {
            return FutureCallback.SUCCEEDED;
        }
        
        @Override
        public Future<Void> shutdown()
        {
            return _shutdown.updateAndGet(fcb->{return fcb==null?newShutdownCallback():fcb;});
        }

        @Override
        public boolean isShutdown()
        {
            return _shutdown.get()!=null;
        }

        public void cancel()
        {
            FutureCallback shutdown = _shutdown.getAndSet(null);
            if (shutdown!=null && !shutdown.isDone())
                shutdown.cancel(true);
        }
        
        public FutureCallback get()
        {
            return _shutdown.get();
        }
    }
}
