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

package org.eclipse.jetty.websocket.jsr356.endpoints;

import java.lang.reflect.Method;
import java.util.ArrayList;

import org.eclipse.jetty.websocket.common.events.ParamList;
import org.eclipse.jetty.websocket.jsr356.endpoints.JsrMethodParameters.Param;

public class JsrMethodParameters extends ArrayList<Param>
{
    public static class Param
    {
        public int index;
        public Class<?> type;
        private boolean valid = false;
        private String pathParamVariable = null;

        public Param(int idx, Class<?> type)
        {
            this.index = idx;
            this.type = type;
        }

        public String getPathParamVariable()
        {
            return this.pathParamVariable;
        }

        public boolean isValid()
        {
            return valid;
        }

        public void setPathParamVariable(String name)
        {
            this.pathParamVariable = name;
        }

        public void setValid(boolean flag)
        {
            this.valid = flag;
        }
    }

    private static final long serialVersionUID = -181761176209945279L;

    public JsrMethodParameters(Method method)
    {
        Class<?> ptypes[] = method.getParameterTypes();
        int len = ptypes.length;
        for (int i = 0; i < len; i++)
        {
            add(new Param(i,ptypes[i]));
        }
    }

    public Class<?>[] containsAny(ParamList validParams)
    {
        for (Class<?>[] params : validParams)
        {
            if (containsParameterSet(params))
            {
                return params;
            }
        }
        return null;
    }

    public boolean containsParameterSet(Class<?>[] paramSet)
    {
        for (Class<?> entry : paramSet)
        {
            boolean found = false;
            for (Param param : this)
            {
                if (param.isValid())
                {
                    continue; // skip
                }
                if (param.type.isAssignableFrom(entry))
                {
                    found = true;
                }
            }
            if (!found)
            {
                return false;
            }
        }
        return true;
    }

    public void setValid(Class<?>[] paramSet)
    {
        for (Class<?> entry : paramSet)
        {
            for (Param param : this)
            {
                if (param.type.isAssignableFrom(entry))
                {
                    param.setValid(true);
                }
            }
        }
    }
}
