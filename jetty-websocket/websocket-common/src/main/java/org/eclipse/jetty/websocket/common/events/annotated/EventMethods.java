//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.events.annotated;

/**
 * A representation of the methods available to call for a particular class.
 */
public class EventMethods
{
    private Class<?> pojoClass;
    public EventMethod onConnect = null;
    public EventMethod onClose = null;
    public EventMethod onBinary = null;
    public EventMethod onText = null;
    public EventMethod onError = null;
    public EventMethod onFrame = null;

    public EventMethods(Class<?> pojoClass)
    {
        this.pojoClass = pojoClass;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (obj == null)
        {
            return false;
        }
        if (getClass() != obj.getClass())
        {
            return false;
        }
        EventMethods other = (EventMethods)obj;
        if (pojoClass == null)
        {
            if (other.pojoClass != null)
            {
                return false;
            }
        }
        else if (!pojoClass.getName().equals(other.pojoClass.getName()))
        {
            return false;
        }
        return true;
    }

    public Class<?> getPojoClass()
    {
        return pojoClass;
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = (prime * result) + ((pojoClass == null)?0:pojoClass.getName().hashCode());
        return result;
    }

    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("EventMethods [pojoClass=");
        builder.append(pojoClass);
        builder.append(", onConnect=");
        builder.append(onConnect);
        builder.append(", onClose=");
        builder.append(onClose);
        builder.append(", onBinary=");
        builder.append(onBinary);
        builder.append(", onText=");
        builder.append(onText);
        builder.append(", onException=");
        builder.append(onError);
        builder.append(", onFrame=");
        builder.append(onFrame);
        builder.append("]");
        return builder.toString();
    }

}
