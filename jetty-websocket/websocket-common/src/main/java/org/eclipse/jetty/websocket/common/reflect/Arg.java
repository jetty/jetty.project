//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.reflect;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * A single argument for a method
 */
public class Arg
{
    private final Class<?> type;
    private Method method;
    private int index;
    private String tag;
    private boolean required = false;

    public Arg(Class<?> type)
    {
        this.type = type;
    }

    public Arg(int idx, Class<?> type)
    {
        this.index = idx;
        this.type = type;
    }

    public Arg(Method method, int idx, Class<?> type)
    {
        this.method = method;
        this.index = idx;
        this.type = type;
    }
    
    public Arg(Arg arg)
    {
        this.method = arg.method;
        this.index = arg.index;
        this.type = arg.type;
        this.tag = arg.tag;
        this.required = arg.required;
    }
    
    public <T extends Annotation> T getAnnotation(Class<T> annoClass)
    {
        if (method == null)
            return null;

        Annotation annos[] = method.getParameterAnnotations()[index];
        if (annos != null || (annos.length > 0))
        {
            for (Annotation anno : annos)
            {
                if (anno.annotationType().equals(annoClass))
                {
                    return (T) anno;
                }
            }
        }
        return null;
    }

    public int getIndex()
    {
        return index;
    }

    public String getName()
    {
        return type.getName();
    }
    
    public String getTag()
    {
        return tag;
    }
    
    public Class<?> getType()
    {
        return type;
    }

    public boolean isArray()
    {
        return type.isArray();
    }

    public boolean isRequired()
    {
        return required;
    }

    public boolean matches(Arg other)
    {
        // If tags exist
        if (this.tag != null)
        {
            // They have to match
            return (this.tag.equals(other.tag));
        }

        // Lastly, if types match, use em
        return (this.type.isAssignableFrom(other.type));
    }

    public Arg required()
    {
        this.required = true;
        return this;
    }

    public Arg setTag(String tag)
    {
        this.tag = tag;
        return this;
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s%d%s]", type.getName(),
                required ? "!" : "", index, tag == null ? "" : "/" + tag);
    }
}
