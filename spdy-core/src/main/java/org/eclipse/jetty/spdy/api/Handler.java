/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
     * @see #failed(Throwable)
     */
    public abstract void completed(C context);

    /**
     * <p>Callback invoked when the operation fails.</p>
     *
     * @param x the reason for the operation failure
     */
    public void failed(Throwable x);

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
        public void failed(Throwable x)
        {
            throw new SPDYException(x);
        }
    }
}
