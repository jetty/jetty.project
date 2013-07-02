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

package org.eclipse.jetty.websocket.jsr356.annotations;

import org.eclipse.jetty.websocket.jsr356.utils.ReflectUtils;

public class Param
{
    /**
     * The various roles of the known parameters.
     */
    public static enum Role
    {
        SESSION,
        ENDPOINT_CONFIG,
        CLOSE_REASON,
        ERROR_CAUSE,
        MESSAGE_TEXT,
        MESSAGE_TEXT_STREAM,
        MESSAGE_BINARY,
        MESSAGE_BINARY_STREAM,
        MESSAGE_PONG,
        MESSAGE_PARTIAL_FLAG,
        PATH_PARAM;

        private static Role[] messageRoles;

        static
        {
            messageRoles = new Role[]
            { MESSAGE_TEXT, MESSAGE_TEXT_STREAM, MESSAGE_BINARY, MESSAGE_BINARY_STREAM, MESSAGE_PONG, };
        }

        public static Role[] getMessageRoles()
        {
            return messageRoles;
        }
    }

    public int index;
    public Class<?> type;
    /*
     * The bound role for this parameter.
     */
    public Role role = null;
    private String pathParamName = null;

    public Param(int idx, Class<?> type)
    {
        this.index = idx;
        this.type = type;
    }

    public void bind(Role role)
    {
        this.role = role;
    }

    public String getPathParamName()
    {
        return this.pathParamName;
    }

    public boolean isValid()
    {
        return this.role != null;
    }

    public void setPathParamName(String name)
    {
        this.pathParamName = name;
    }

    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder();
        str.append("Param[");
        str.append("index=").append(index);
        str.append(",type=").append(ReflectUtils.toShortName(type));
        str.append(",role=").append(role);
        if (pathParamName != null)
        {
            str.append(",pathParamName=").append(pathParamName);
        }
        str.append(']');
        return str.toString();
    }

    public void unbind()
    {
        this.role = null;
    }
}