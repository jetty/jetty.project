//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.jaas.callback;

import javax.security.auth.callback.Callback;

/**
 * ObjectCallback
 * <p>
 * Can be used as a LoginModule Callback to
 * obtain a user's credential as an Object, rather than
 * a char[], to which some credentials may not be able
 * to be converted
 */
public class ObjectCallback implements Callback
{
    protected Object _object;

    public void setObject(Object o)
    {
        _object = o;
    }

    public Object getObject()
    {
        return _object;
    }

    public void clearObject()
    {
        _object = null;
    }
}
