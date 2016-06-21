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

package org.eclipse.jetty.websocket.jsr356.annotations;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Map;

import javax.websocket.DecodeException;
import javax.websocket.Decoder;

import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.common.events.annotated.CallableMethod;
import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;
import org.eclipse.jetty.websocket.jsr356.JsrSession;
import org.eclipse.jetty.websocket.jsr356.annotations.Param.Role;

public abstract class JsrCallable extends CallableMethod
{
    protected final Param[] params;
    protected final Object[] args;
    protected int idxSession = -1;
    protected int idxConfig = -1;

    public JsrCallable(Class<?> pojo, Method method)
    {
        super(pojo,method);

        Class<?> ptypes[] = method.getParameterTypes();
        Annotation pannos[][] = method.getParameterAnnotations();
        int len = ptypes.length;
        params = new Param[len];
        for (int i = 0; i < len; i++)
        {
            params[i] = new Param(i,ptypes[i],pannos[i]);
        }

        args = new Object[len];
    }

    /**
     * Copy Constructor
     * @param copy the JsrCallable to copy from 
     */
    public JsrCallable(JsrCallable copy)
    {
        this(copy.getPojo(),copy.getMethod());
        this.idxSession = copy.idxSession;
        this.idxConfig = copy.idxConfig;
        System.arraycopy(copy.params,0,this.params,0,params.length);
        System.arraycopy(copy.args,0,this.args,0,args.length);
    }

    protected void assertRoleRequired(int index, String description)
    {
        if (index < 0)
        {
            StringBuilder err = new StringBuilder();
            err.append("Unable to find parameter with role [");
            err.append(description).append("] in method: ");
            err.append(ReflectUtils.toString(pojo,method));
            throw new InvalidSignatureException(err.toString());
        }
    }

    /**
     * Search the list of parameters for first one matching the role specified.
     * 
     * @param role
     *            the role to look for
     * @return the index for the role specified (or -1 if not found)
     */
    protected int findIndexForRole(Role role)
    {
        Param param = findParamForRole(role);
        if (param != null)
        {
            return param.index;
        }
        return -1;
    }

    /**
     * Find first param for specified role.
     * 
     * @param role
     *            the role specified
     * @return the param (or null if not found)
     */
    protected Param findParamForRole(Role role)
    {
        for (Param param : params)
        {
            if (param.role == role)
            {
                return param;
            }
        }
        return null;
    }

    public Param[] getParams()
    {
        return params;
    }

    public void init(JsrSession session)
    {
        // Default for the session.
        // Session is an optional parameter (always)
        idxSession = findIndexForRole(Param.Role.SESSION);
        if (idxSession >= 0)
        {
            args[idxSession] = session;
        }

        // Optional EndpointConfig
        idxConfig = findIndexForRole(Param.Role.ENDPOINT_CONFIG);
        if (idxConfig >= 0)
        {
            args[idxConfig] = session.getEndpointConfig();
        }

        // Default for the path parameters
        // PathParam's are optional parameters (always)
        Map<String, String> pathParams = session.getPathParameters();
        if ((pathParams != null) && (pathParams.size() > 0))
        {
            for (Param param : params)
            {
                if (param.role == Role.PATH_PARAM)
                {
                    int idx = param.index;
                    String rawvalue = pathParams.get(param.getPathParamName());

                    Decoder decoder = session.getDecoderFactory().getDecoderFor(param.type);
                    if (decoder instanceof Decoder.Text<?>)
                    {
                        Decoder.Text<?> textDecoder = (Decoder.Text<?>)decoder;
                        try
                        {
                            args[idx] = textDecoder.decode(rawvalue);
                        }
                        catch (DecodeException e)
                        {
                            session.notifyError(e);
                        }
                    }
                    else
                    {
                        throw new InvalidWebSocketException("PathParam decoders must use Decoder.Text");
                    }
                }
            }
        }
    }

    /**
     * The Type of Class a {@link Decoder} should be created to produce.
     * @param decodingType the type of class a Decoder should be created to produce
     */
    public abstract void setDecodingType(Class<?> decodingType);
}
