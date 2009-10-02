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

import java.util.Map;

import org.slf4j.spi.MDCAdapter;

public class CentralMDCAdapter implements MDCAdapter
{

    public void clear()
    {
        CentralMDC.clear();
    }

    public String get(String key)
    {
        return CentralMDC.get(key);
    }

    @SuppressWarnings("unchecked")
    public Map getCopyOfContextMap()
    {
        return CentralMDC.getContextMap();
    }

    public void put(String key, String value)
    {
        CentralMDC.put(key,value);
    }

    public void remove(String key)
    {
        CentralMDC.remove(key);
    }

    @SuppressWarnings("unchecked")
    public void setContextMap(Map contextMap)
    {
        CentralMDC.setContextMap(contextMap);
    }
}
