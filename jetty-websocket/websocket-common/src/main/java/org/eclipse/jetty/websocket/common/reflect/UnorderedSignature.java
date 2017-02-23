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

package org.eclipse.jetty.websocket.common.reflect;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.reflect.DynamicArgs.Signature;
import org.eclipse.jetty.websocket.common.util.ReflectUtils;

public class UnorderedSignature implements Signature, Predicate<Method>
{
    private class SelectedArg extends Arg
    {
        private boolean selected = false;
        
        public SelectedArg(Arg arg)
        {
            super(arg);
        }
    
        public boolean isSelected()
        {
            return selected;
        }
    
        public void selected()
        {
            this.selected = true;
        }
    }
    
    private final static Logger LOG = Log.getLogger(UnorderedSignature.class);
    private final Arg[] callArgs;
    
    public UnorderedSignature(Arg... args)
    {
        this.callArgs = args;
    }
    
    @Override
    public Arg[] getCallArgs()
    {
        return this.callArgs;
    }
    
    @Override
    public Predicate<Method> getPredicate()
    {
        return this;
    }
    
    @Override
    @Deprecated
    public boolean test(Method method)
    {
        return getArgMapping(method, false, callArgs) != null;
    }
    
    public int[] getArgMapping(Method method)
    {
        return getArgMapping(method, false, callArgs);
    }
    
    public void appendDescription(StringBuilder str)
    {
        str.append('(');
        boolean delim = false;
        for (Arg arg : callArgs)
        {
            if (delim)
            {
                str.append(',');
            }
            str.append(' ');
            str.append(arg.getName());
            if (arg.isArray())
            {
                str.append("[]");
            }
            delim = true;
        }
        str.append(')');
    }
    
    /**
     * Identify mapping of argument indexes of the callArgs to method Args.
     * <p>
     * The callArgs is what the websocket implementation code is
     * using to call the method.
     * </p>
     * <p>
     * The method Args are what the user endpoint method args
     * are declared as.
     * </p>
     *
     * @param method the method that we want to eventually call
     * @param throwOnFailure true to toss a {@link DynamicArgsException} if there is a problem
     * attempting to identify the mapping.  false to debug log the issue.
     * @param callArgs the calling args for this signature
     */
    public int[] getArgMapping(Method method, boolean throwOnFailure, Arg... callArgs)
    {
        int callArgsLen = callArgs.length;
        
        // Figure out mapping of calling args to method args
        Class<?> paramTypes[] = method.getParameterTypes();
        int paramTypesLength = paramTypes.length;
        
        // Method argument array pointing to index in calling array
        int argMapping[] = new int[paramTypesLength];
        int argMappingLength = argMapping.length;
        
        // ServiceLoader for argument identification plugins
        List<ArgIdentifier> argIdentifiers = DynamicArgs.lookupArgIdentifiers();
        Arg methodArgs[] = new Arg[paramTypesLength];
        for (int pi = 0; pi < paramTypesLength; pi++)
        {
            methodArgs[pi] = new Arg(method, pi, paramTypes[pi]);
            
            // Supplement method argument identification from plugins
            for (ArgIdentifier argId : argIdentifiers)
                methodArgs[pi] = argId.apply(methodArgs[pi]);
        }
        
        // Selected Args
        SelectedArg selectedArgs[] = new SelectedArg[callArgs.length];
        for (int ci = 0; ci < selectedArgs.length; ci++)
        {
            selectedArgs[ci] = new SelectedArg(callArgs[ci]);
        }
        
        // Iterate through mappings, looking for a callArg that fits it
        for (int ai = 0; ai < argMappingLength; ai++)
        {
            int ref = -1;
            
            // Find reference to argument in callArgs
            for (int ci = 0; ci < callArgsLen; ci++)
            {
                if (!selectedArgs[ci].selected && methodArgs[ai].matches(selectedArgs[ci]))
                {
                    selectedArgs[ci].selected();
                    ref = ci;
                    break;
                }
            }
            
            if (ref < 0)
            {
                StringBuilder err = new StringBuilder();
                err.append("Unable to map type [");
                err.append(methodArgs[ai].getType());
                err.append("] in method ");
                ReflectUtils.append(err, method);
                err.append(" to calling args: (");
                boolean delim = false;
                for (Arg arg : callArgs)
                {
                    if (delim)
                        err.append(", ");
                    err.append(arg);
                    delim = true;
                }
                err.append(")");
                
                if (throwOnFailure)
                {
                    throw new DynamicArgsException(err.toString());
                }
                else
                {
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug("{}", err.toString());
                    }
                    return null;
                }
            }
            
            argMapping[ai] = ref;
        }
        
        // Ensure that required arguments are present in the mapping
        for (int ci = 0; ci < callArgsLen; ci++)
        {
            if (selectedArgs[ci].isRequired() && !selectedArgs[ci].isSelected())
            {
                StringBuilder err = new StringBuilder();
                err.append("Unable to find required type [");
                err.append(callArgs[ci].getType());
                err.append("] in method ");
                ReflectUtils.append(err, method);
                
                if (throwOnFailure)
                    throw new DynamicArgsException(err.toString());
                else
                {
                    LOG.debug("{}", err.toString());
                    return null;
                }
            }
        }
        
        return argMapping;
    }
    
    @Override
    public BiFunction<Object, Object[], Object> getInvoker(Method method, Arg... callArgs)
    {
        int argMapping[] = getArgMapping(method, true, callArgs);
        
        // Return function capable of calling method
        return new UnorderedParamsFunction(method, argMapping);
    }
    
    /**
     * Generate BiFunction for this signature.
     *
     * @param method the method to get the invoker function for
     * @return BiFunction of Endpoint Object, Call Args, Return Type
     */
    public BiFunction<Object, Object[], Object> newFunction(Method method)
    {
        int argMapping[] = getArgMapping(method, true, this.callArgs);
        
        // Return function capable of calling method
        return new UnorderedParamsFunction(method, argMapping);
    }
    
    public static class UnorderedParamsFunction
            implements BiFunction<Object, Object[], Object>
    {
        private final Method method;
        private final int paramTypesLength;
        private final int argMapping[];
        
        public UnorderedParamsFunction(Method method, int argMapping[])
        {
            this.method = method;
            this.paramTypesLength = method.getParameterTypes().length;
            this.argMapping = argMapping;
        }
        
        @Override
        public Object apply(Object obj, Object[] potentialArgs)
        {
            Object args[] = new Object[paramTypesLength];
            for (int i = 0; i < paramTypesLength; i++)
            {
                args[i] = potentialArgs[argMapping[i]];
            }
            try
            {
                return method.invoke(obj, args);
            }
            catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e)
            {
                StringBuilder err = new StringBuilder();
                err.append("Unable to call: ");
                ReflectUtils.append(err, obj.getClass(), method);
                err.append(" [with ");
                boolean delim = false;
                for (Object arg : args)
                {
                    if (delim)
                        err.append(", ");
                    if (arg == null)
                    {
                        err.append("<null>");
                    }
                    else
                    {
                        err.append(arg.getClass().getSimpleName());
                    }
                    delim = true;
                }
                err.append("]");
                throw new DynamicArgsException(err.toString(), e);
            }
        }
    }
    
}
