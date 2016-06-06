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

package org.eclipse.jetty.util;

import org.eclipse.jetty.util.log.Log;

/**
 * <p>A callback abstraction that handles completed/failed events of asynchronous operations.</p>
 *
 * @param <C> the type of the context object
 */
public interface Promise<C>
{
    /**
     * <p>Callback invoked when the operation completes.</p>
     *
     * @param result the context
     * @see #failed(Throwable)
     */
    public abstract void succeeded(C result);

    /**
     * <p>Callback invoked when the operation fails.</p>
     *
     * @param x the reason for the operation failure
     */
    public void failed(Throwable x);

    /**
     * <p>Empty implementation of {@link Promise}</p>
     *
     * @param <C> the type of the context object
     */
    public static class Adapter<C> implements Promise<C>
    {
        @Override
        public void succeeded(C result)
        {
        }

        @Override
        public void failed(Throwable x)
        {
            Log.getLogger(this.getClass()).warn(x);
        }
    }

    public static abstract class Wrapper<W> implements Promise<W>
    {
        private final Promise<W> promise;

        public Wrapper(Promise<W> promise)
        {
            this.promise = promise;
        }

        public Promise<W> getPromise()
        {
            return promise;
        }

        public Promise<W> unwrap()
        {
            Promise<W> result = promise;
            while (true)
            {
                if (result instanceof Wrapper)
                    result = ((Wrapper<W>)result).unwrap();
                else
                    break;
            }
            return result;
        }
    }
}
