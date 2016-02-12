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

package org.eclipse.jetty.websocket.common.util;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Provide argument utilities for working with methods that
 * have a dynamic assortment of arguments.
 * <ol>
 * <li>Can identify a set of parameters as matching the Builder</li>
 * <li>Can create a DynamicArgs for the matched signature</li>
 * <li>Can create an argument array for the provided potential arguments,
 *     suitable to be used with {@link Method#invoke(Object, Object...)}</li>
 * </ol>
 */
public class DynamicArgs
{
    public static interface Signature
    {
        public boolean matches(Class<?>[] types);
        public void appendDescription(StringBuilder str);
        public Object[] toArgs(Object[] potentialArgs, int[] argReferences);
    }
    
    public static class UnorderedSignature implements Signature
    {
        private final Class<?>[] validParams;
        private int[] validParamsIndex;

        public UnorderedSignature(Class<?> ... classes)
        {
            this.validParams = classes;
        }

        public UnorderedSignature indexedAs(int ... index)
        {
            this.validParamsIndex = index;
            return this;
        }

        public Class<?>[] getParams()
        {
            return validParams;
        }

        public int[] getIndex()
        {
            return validParamsIndex;
        }

        public int size()
        {
            return validParams.length;
        }

        public boolean matches(Class<?>[] types)
        {
            // Matches if the provided types
            // match the valid params in any order
            
            if (types.length != validParams.length)
                return false;
            int len = validParams.length;
            for (int i = 0; i < len; i++)
            {
                if (!validParams[i].equals(types[i]))
                    return false;
            }
            return true;
        }

        public void appendDescription(StringBuilder str)
        {
            str.append('(');
            boolean delim = false;
            for (Class<?> type : validParams)
            {
                if (delim)
                {
                    str.append(',');
                }
                str.append(' ');
                str.append(type.getName());
                if (type.isArray())
                {
                    str.append("[]");
                }
                delim = true;
            }
            str.append(')');
        }

        public Object[] toArgs(Object[] potentialArgs, int[] argReferences)
        {
            int slen = size();
            int plen = potentialArgs.length;
            Object args[] = new Object[slen];
            for (int sidx = 0; sidx < slen; sidx++)
            {
                int wantIdx = validParamsIndex[sidx];
                for (int argIdx = 0; argIdx < plen; argIdx++)
                {
                    if (argReferences[argIdx] == wantIdx)
                        args[sidx] = potentialArgs[argIdx];
                }
            }
            return args;
        }
    }

    public static class ExactSignature implements Signature
    {
        private final Class<?>[] params;
        private int[] index;

        public ExactSignature(Class<?> ... classes)
        {
            this.params = classes;
        }

        public ExactSignature indexedAs(int ... index)
        {
            this.index = index;
            return this;
        }

        public Class<?>[] getParams()
        {
            return params;
        }

        public int[] getIndex()
        {
            return index;
        }

        public int size()
        {
            return params.length;
        }

        public boolean matches(Class<?>[] types)
        {
            if (types.length != params.length)
                return false;
            int len = params.length;
            for (int i = 0; i < len; i++)
            {
                if (!params[i].equals(types[i]))
                    return false;
            }
            return true;
        }

        public void appendDescription(StringBuilder str)
        {
            str.append('(');
            boolean delim = false;
            for (Class<?> type : params)
            {
                if (delim)
                {
                    str.append(',');
                }
                str.append(' ');
                str.append(type.getName());
                if (type.isArray())
                {
                    str.append("[]");
                }
                delim = true;
            }
            str.append(')');
        }

        public Object[] toArgs(Object[] potentialArgs, int[] argReferences)
        {
            int slen = size();
            int plen = potentialArgs.length;
            Object args[] = new Object[slen];
            for (int sidx = 0; sidx < slen; sidx++)
            {
                int wantIdx = index[sidx];
                for (int argIdx = 0; argIdx < plen; argIdx++)
                {
                    if (argReferences[argIdx] == wantIdx)
                        args[sidx] = potentialArgs[argIdx];
                }
            }
            return args;
        }
    }

    public static class Builder
    {
        private List<Signature> signatures = new ArrayList<>();

        public DynamicArgs build(Method method)
        {
            Class<?> paramTypes[] = method.getParameterTypes();
            for (Signature sig : signatures)
            {
                if (sig.matches(paramTypes))
                {
                    return new DynamicArgs(sig);
                }
            }

            return null;
        }

        public boolean hasMatchingSignature(Method method)
        {
            Class<?> paramTypes[] = method.getParameterTypes();
            for (Signature sig : signatures)
            {
                if (sig.matches(paramTypes))
                {
                    return true;
                }
            }
            return false;
        }
        
        public Builder addSignature(Signature sig)
        {
            signatures.add(sig);
            return this;
        }

        public List<Signature> getSignatures()
        {
            return this.signatures;
        }
    }

    private final Signature signature;
    private int argReferences[];

    public DynamicArgs(Signature sig)
    {
        this.signature = sig;
    }

    public void setArgReferences(int... argIndex)
    {
        this.argReferences = argIndex;
    }

    public Object[] toArgs(Object... potentialArgs)
    {
        return signature.toArgs(potentialArgs,argReferences);
    }
}
