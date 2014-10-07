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

package org.eclipse.jetty.util;

/**
 * <p>A callback abstraction that handles completed/failed events of asynchronous operations.</p>
 *
 * <p>Semantically this is equivalent to an optimise Promise&lt;Void&gt;, but callback is a more meaningful 
 * name than EmptyPromise</p>
 */
public interface Callback
{
    /**
     * <p>Callback invoked when the operation completes.</p>
     *
     * @see #failed(Throwable)
     */
    public abstract void succeeded();

    /**
     * <p>Callback invoked when the operation fails.</p>
     * @param x the reason for the operation failure
     */
    public void failed(Throwable x);

    /**
     * <p>Empty implementation of {@link Callback}</p>
     */
    public static class Adapter implements Callback
    {
        /**
         * Instance of Adapter that can be used when the callback methods need an empty
         * implementation without incurring in the cost of allocating a new Adapter object.
         */
        public static final Adapter INSTANCE = new Adapter();

        @Override
        public void succeeded()
        {
        }

        @Override
        public void failed(Throwable x)
        {
        }
    }
}
