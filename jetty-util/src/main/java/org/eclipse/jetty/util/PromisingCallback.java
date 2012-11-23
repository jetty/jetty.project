//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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


/* ------------------------------------------------------------ */
/** A Callback that can fulfil a Promise.
 * <p>When this callback is successful, it will call the 
 * {@link Promise#succeeded(Object)} method of the wrapped Promise,
 * passing the held result.
 * @param <R> The type of the result for the promise.
 */
public class PromisingCallback<R> implements Callback
{
    private final Promise<R> _promise;
    private final R _result;

    protected PromisingCallback(Promise<R> promise)
    {
        _promise=promise;
        _result=(R)this;
    }
    
    public PromisingCallback(Promise<R> promise, R result)
    {
        _promise=promise;
        _result=result;
    }
    
    @Override
    public void succeeded()
    {
        if (_promise!=null)
            _promise.succeeded(_result);
    }

    @Override
    public void failed(Throwable x)
    {
        if (_promise!=null)
            _promise.failed(x);
    }

}
