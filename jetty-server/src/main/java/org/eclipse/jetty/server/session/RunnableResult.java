//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.session;

/**
 * Small utility class to allow us to
 * return a result and an Exception
 * from invocation of Runnables.
 *
 * @param <V> the type of the result.
 */
public class RunnableResult<V>
{
    private V _result;
    private Exception _exception;
    
    public void setResult(V result)
    {
        _result = result;
    }
    
    public void setException(Exception exception)
    {
        _exception = exception;
    }
    
    public void throwIfException() throws Exception
    {
        if (_exception != null)
            throw _exception;
    }
    
    public V getOrThrow() throws Exception
    {
        throwIfException();
        return _result;
    }
}