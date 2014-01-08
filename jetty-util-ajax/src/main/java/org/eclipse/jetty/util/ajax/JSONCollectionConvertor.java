//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.ajax;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import org.eclipse.jetty.util.Loader;

public class JSONCollectionConvertor implements JSON.Convertor
{
    public void toJSON(Object obj, JSON.Output out)
    {
        out.addClass(obj.getClass());
        out.add("list", ((Collection)obj).toArray());
    }

    public Object fromJSON(Map object)
    {
        try
        {
            Collection result = (Collection)Loader.loadClass(getClass(), (String)object.get("class")).newInstance();
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
