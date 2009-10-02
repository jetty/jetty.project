// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.logging.impl;

import java.util.HashMap;

final public class ThreadLocalMap extends InheritableThreadLocal<HashMap<String, String>>
{
    @Override
    @SuppressWarnings("unchecked")
    protected HashMap<String, String> childValue(HashMap<String, String> parentValue)
    {
        if (parentValue != null)
        {
            return (HashMap<String, String>)parentValue.clone();
        }
        else
        {
            return null;
        }
    }
}
