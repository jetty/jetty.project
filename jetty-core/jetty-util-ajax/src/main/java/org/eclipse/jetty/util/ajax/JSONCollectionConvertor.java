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

package org.eclipse.jetty.util.ajax;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import org.eclipse.jetty.util.Loader;

public class JSONCollectionConvertor implements JSON.Convertor
{
    @Override
    public void toJSON(Object obj, JSON.Output out)
    {
        out.addClass(obj.getClass());
        Collection<?> collection = (Collection<?>)obj;
        out.add("list", collection.toArray());
    }

    @Override
    public Object fromJSON(Map<String, Object> object)
    {
        try
        {
            Class<?> cls = Loader.loadClass((String)object.get("class"));
            @SuppressWarnings("unchecked")
            Collection<Object> result = (Collection<Object>)cls.getConstructor().newInstance();
            Collections.addAll(result, (Object[])object.get("list"));
            return result;
        }
        catch (Exception x)
        {
            if (x instanceof RuntimeException)
                throw (RuntimeException)x;
            throw new RuntimeException(x);
        }
    }
}
