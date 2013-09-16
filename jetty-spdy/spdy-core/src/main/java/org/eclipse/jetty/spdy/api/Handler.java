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

package org.eclipse.jetty.spdy.api;

/**
 * <p>A callback abstraction that handles completed/failed events of asynchronous operations.</p>
 * <p>Instances of this class capture a context that is made available on the completion callback.</p>
 *
 * @param <C> the type of the context object
 */
public interface Handler<C>
{
    /**
     * <p>Callback invoked when the operation completes.</p>
     *
     * @param context the context
     * @see #failed(Object, Throwable)
     */
    public abstract void completed(C context);

    /**
     * <p>Callback invoked when the operation fails.</p>
     * @param context the context
     * @param x the reason for the operation failure
     */
    public void failed(C context, Throwable x);

    /**
     * <p>Empty implementation of {@link Handler}</p>
     *
     * @param <C> the type of the context object
     */
    public static class Adapter<C> implements Handler<C>
    {
        @Override
        public void completed(C context)
        {
        }

        @Override
        public void failed(C context, Throwable x)
        {
        }
    }
}
