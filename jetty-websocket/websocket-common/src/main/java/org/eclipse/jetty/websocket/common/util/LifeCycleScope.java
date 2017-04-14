//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.util;

import java.util.function.Supplier;

import org.eclipse.jetty.util.component.LifeCycle;

/**
 * Simple {@link AutoCloseable} to allow Jetty {@link LifeCycle} components to
 * be managed using {@code try-with-resources} techniques.
 * <p>
 *     {@link LifeCycle#start()} occurs at constructor.
 *     {@link LifeCycle#stop()} occurs at {@link #close()}.
 * </p>
 *
 * @param <T> the {@link LifeCycle} to have resource managed
 */
public class LifeCycleScope<T extends LifeCycle> implements AutoCloseable, Supplier<T>
{
    private final T lifecycle;
    
    public LifeCycleScope(T lifecycle) throws Exception
    {
        this.lifecycle = lifecycle;
        this.lifecycle.start();
    }
    
    @Override
    public void close() throws Exception
    {
        this.lifecycle.stop();
    }
    
    @Override
    public T get()
    {
        return this.lifecycle;
    }
}
