//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util;

import java.util.Iterator;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Spliterator;
import java.util.function.Consumer;

class ServiceLoaderSpliterator<T> implements Spliterator<ServiceLoader.Provider<T>>
{
    private final Iterator<T> iterator;

    public ServiceLoaderSpliterator(ServiceLoader<T> serviceLoader)
    {
        iterator = serviceLoader.iterator();
    }

    @Override
    public boolean tryAdvance(Consumer<? super ServiceLoader.Provider<T>> action)
    {
        Provider<T> next = new Provider<>();
        try
        {
            if (!iterator.hasNext())
                return false;
            next.setServiceProvider(iterator.next());
        }
        catch (Throwable t)
        {
            next.setError(t);
        }

        action.accept(next);
        return true;
    }

    @Override
    public Spliterator<ServiceLoader.Provider<T>> trySplit()
    {
        return null;
    }

    @Override
    public long estimateSize()
    {
        return Long.MAX_VALUE;
    }

    @Override
    public int characteristics()
    {
        return Spliterator.ORDERED;
    }

    private static class Provider<T> implements ServiceLoader.Provider<T>
    {
        private T serviceProvider;
        private Throwable error;

        public void setServiceProvider(T serviceProvider)
        {
            this.serviceProvider = serviceProvider;
        }

        public void setError(Throwable error)
        {
            this.error = error;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Class<? extends T> type()
        {
            return (Class<? extends T>)get().getClass();
        }

        @Override
        public T get()
        {
            if (error != null)
                throw new ServiceConfigurationError("", error);
            return serviceProvider;
        }
    }
}
