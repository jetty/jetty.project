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

package org.eclipse.jetty.plus.servlet;

import org.eclipse.jetty.plus.annotation.InjectionCollection;
import org.eclipse.jetty.plus.annotation.LifeCycleCallbackCollection;

/**
 * ServletHandler
 *
 *
 */
public class ServletHandler extends org.eclipse.jetty.servlet.ServletHandler
{

    private InjectionCollection _injections = null;
    private LifeCycleCallbackCollection _callbacks = null;
    


    /**
     * @return the callbacks
     */
    public LifeCycleCallbackCollection getCallbacks()
    {
        return _callbacks;
    }



    /**
     * @param callbacks the callbacks to set
     */
    public void setCallbacks(LifeCycleCallbackCollection callbacks)
    {
        this._callbacks = callbacks;
    }



    /**
     * @return the injections
     */
    public InjectionCollection getInjections()
    {
        return _injections;
    }



    /**
     * @param injections the injections to set
     */
    public void setInjections(InjectionCollection injections)
    {
        this._injections = injections;
    }
    

}
