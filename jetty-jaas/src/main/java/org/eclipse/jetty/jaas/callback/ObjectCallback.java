//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
