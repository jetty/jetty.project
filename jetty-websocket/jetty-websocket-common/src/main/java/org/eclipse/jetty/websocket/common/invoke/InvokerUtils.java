//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.invoke;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.websocket.common.util.ReflectUtils;

public class InvokerUtils
{
    public static class Arg
    {
        private final Class<?> type;
        private boolean required = false;
        private String name;

        public Arg(Class<?> type)
        {
            this.type = type;
        }

        public Arg(Class<?> type, String name)
        {
            this.type = type;
            this.name = name;
        }

        public String getName()
        {
            return name;
        }

        public boolean matches(Arg other)
        {
            // If tags exist
            if (this.name != null)
            {
                // They have to match
                return (this.name.equals(other.name));
            }

            // Lastly, if types match, use em
            return (this.type.isAssignableFrom(other.type));
        }

        public Arg required()
        {
            this.required = true;
            return this;
        }

        public Class<?> getType()
        {
            return type;
        }

        public boolean isRequired()
        {
            return required;
        }
    }

    public interface ParamIdentifier
    {
        Arg getParamArg(Method method, Class<?> paramType, int idx);
    }

    private static class ParamIdentity implements ParamIdentifier
    {
        @Override
        public Arg getParamArg(Method method, Class<?> paramType, int idx)
        {
            return new Arg(paramType);
        }
    }

    public static final ParamIdentifier PARAM_IDENTITY = new ParamIdentity();

    /**
     * Build a MethodHandle that can call the method with the calling args provided.
     * <p>
     * Might need to drop calling args and/or reorder the calling args to fit
     * the actual method being called.
     * </p>
     */
    public static MethodHandle mutatedInvoker(Class<?> targetClass, Method method, Arg... callingArgs)
    {
        return mutatedInvoker(targetClass, method, PARAM_IDENTITY, callingArgs);
    }

    /**
     * Create a MethodHandle that performs the following layers
     * <ol>
     * <li>{@link MethodHandles#permuteArguments(MethodHandle, MethodType, int...)} - moving calling Args around
     * to fit actual actual method parameter arguments (in proper order), with remaining (unused) calling args afterwords</li>
     * <li>{@link MethodHandles#dropArguments(MethodHandle, int, Class[])} - to drop the unused calling args</li>
     * <li>{@link MethodHandle#invoke(Object...)} - to call the specific method</li>
     * </ol>
     *
     * @param targetClass the target class for invocations of the resulting MethodHandle (also known as parameter 0)
     * @param method the method to invoke
     * @param paramIdentifier the mechanism to identify parameters in method
     * @param callingArgs the calling arguments
     * @return the MethodHandle for this set of CallingArgs
     * @throws RuntimeException when unable to fit Calling Args to Parameter Types
     */
    public static MethodHandle mutatedInvoker(Class<?> targetClass, Method method, ParamIdentifier paramIdentifier, Arg... callingArgs)
    {
        return mutatedInvoker(targetClass, true, method, paramIdentifier, callingArgs);
    }

    /**
     * Create an optional MethodHandle that performs the following layers.
     * <ol>
     * <li>{@link MethodHandles#permuteArguments(MethodHandle, MethodType, int...)} - moving calling Args around
     * to fit actual actual method parameter arguments (in proper order), with remaining (unused) calling args afterwords</li>
     * <li>{@link MethodHandles#dropArguments(MethodHandle, int, Class[])} - to drop the unused calling args</li>
     * <li>{@link MethodHandle#invoke(Object...)} - to call the specific method</li>
     * </ol>
     *
     * @param targetClass the target class for invocations of the resulting MethodHandle (also known as parameter 0)
     * @param method the method to invoke
     * @param paramIdentifier the mechanism to identify parameters in method
     * @param callingArgs the calling arguments
     * @return the MethodHandle for this set of CallingArgs, or null if not possible to create MethodHandle with CallingArgs to provided method
     */
    public static MethodHandle optionalMutatedInvoker(Class<?> targetClass, Method method, ParamIdentifier paramIdentifier, Arg... callingArgs)
    {
        return mutatedInvoker(targetClass, false, method, paramIdentifier, callingArgs);
    }

    private static MethodHandle mutatedInvoker(Class<?> targetClass, boolean throwOnFailure, Method method, ParamIdentifier paramIdentifier, Arg... callingArgs)
    {
        Class<?> parameterTypes[] = method.getParameterTypes();

        // Build up Arg list representing the MethodHandle parameters
        // ParamIdentifier is used to find named parameters (like javax.websocket's @PathParam declaration)
        boolean hasNamedParamArgs = false;
        Arg parameterArgs[] = new Arg[parameterTypes.length + 1];
        parameterArgs[0] = new Arg(targetClass); // first type is always the calling object instance type
        for (int i = 0; i < parameterTypes.length; i++)
        {
            Arg arg = paramIdentifier.getParamArg(method, parameterTypes[i], i);
            if (arg.name != null)
            {
                hasNamedParamArgs = true;
            }
            parameterArgs[i + 1] = arg;
        }

        // Parameter to Calling Argument mapping.
        // The size of this array must be the the same as the parameterArgs array (or bigger)
        if (callingArgs.length < parameterTypes.length)
        {
            if (!throwOnFailure)
            {
                return null;
            }

            StringBuilder err = new StringBuilder();
            err.append("Target method ");
            ReflectUtils.append(err, targetClass, method);
            err.append(" contains too many parameters and cannot be mapped to expected callable args ");
            appendTypeList(err, callingArgs);
            throw new InvalidSignatureException(err.toString());
        }

        // Establish MethodType for supplied calling args
        boolean hasNamedCallingArgs = false;
        List<Class<?>> cTypes = new ArrayList<>();
        cTypes.add(targetClass); // targetClass always at index 0
        for (int i = 0; i < callingArgs.length; i++)
        {
            Arg arg = callingArgs[i];
            if (arg.name != null)
            {
                hasNamedCallingArgs = true;
            }
            cTypes.add(arg.getType());
        }
        MethodType callingType = MethodType.methodType(method.getReturnType(), cTypes);

        // Create low level MethodHandle
        MethodHandles.Lookup lookup = MethodHandles.lookup();

        try
        {
            // Low level invoker.
            // We intentionally do not use lookup#unreflect() as that will incorrectly preserve
            // the calling 'refc' type of where the method is declared, not the targetClass.
            // That behavior of #unreflect() results in a MethodType referring to the
            // base/abstract/interface where the method is declared, and not the targetClass
            MethodType rawType = MethodType.methodType(method.getReturnType(), method.getParameterTypes());
            MethodHandle methodHandle = lookup.findVirtual(targetClass, method.getName(), rawType);

            // If callingType and rawType are the same (and there's no named args),
            // then there's no need to reorder / permute / drop args
            if (!hasNamedCallingArgs && !hasNamedParamArgs && rawType.equals(callingType))
            {
                return methodHandle;
            }

            // If we reached this point, then we know that the callingType and rawType don't
            // match, so we have to drop and/or permute(reorder) the arguments

            // Mapping will be same size as callingType (to compensate for targetClass at index 0)
            int reorderMap[] = new int[callingType.parameterCount()];
            Arrays.fill(reorderMap, -1);
            reorderMap[0] = 0; // always references targetClass

            // To track which callingArgs have been used.
            // If a callingArg is used, it is used only once.
            boolean usedCallingArgs[] = new boolean[callingArgs.length];
            Arrays.fill(usedCallingArgs, false);

            // Iterate through each parameterArg and attempt to find an associated callingArg
            for (int pi = 1; pi < parameterArgs.length; pi++)
            {
                int ref = -1;

                // Find a reference to argument in callArgs
                for (int ci = 0; ci < callingArgs.length; ci++)
                {
                    if (!usedCallingArgs[ci] && parameterArgs[pi].matches(callingArgs[ci]))
                    {
                        ref = ci + 1; // add 1 to compensate for parameter 0
                        usedCallingArgs[ci] = true;
                        break;
                    }
                }

                // Didn't find an unused callingArg that fits this parameterArg
                if (ref < 0)
                {
                    if (!throwOnFailure)
                    {
                        return null;
                    }

                    StringBuilder err = new StringBuilder();
                    err.append("Invalid mapping of type [");
                    err.append(parameterArgs[pi].getType());
                    err.append("] in method ");
                    ReflectUtils.append(err, method);
                    err.append(" to calling args ");
                    appendTypeList(err, callingArgs);

                    throw new InvalidSignatureException(err.toString());
                }

                reorderMap[pi] = ref;
            }

            // Remaining unused callingArgs are to be placed at end of specified reorderMap
            for (int ri = parameterArgs.length; ri <= reorderMap.length; ri++)
            {
                for (int uci = 0; uci < usedCallingArgs.length; uci++)
                {
                    if (usedCallingArgs[uci] == false)
                    {
                        if (callingArgs[uci].required)
                        {
                            if (!throwOnFailure)
                            {
                                return null;
                            }

                            StringBuilder err = new StringBuilder();
                            err.append("Missing required argument [");
                            err.append(callingArgs[uci].getType().getName());
                            err.append("] in method ");
                            ReflectUtils.append(err, method);

                            throw new InvalidSignatureException(err.toString());
                        }

                        reorderMap[ri] = uci + 1; // compensate for parameter 0
                        ri++;
                    }
                }
            }

            // Drop excess (not mapped to a method parameter) calling args
            int idxDrop = parameterArgs.length;
            int dropLength = reorderMap.length - idxDrop;
            if (dropLength > 0)
            {
                List<Class<?>> dropTypes = new ArrayList<>();
                for (int i = 0; i < dropLength; i++)
                {
                    int callingTypeIdx = reorderMap[idxDrop + i];
                    dropTypes.add(callingType.parameterType(callingTypeIdx));
                }
                methodHandle = MethodHandles.dropArguments(methodHandle, idxDrop, dropTypes);
            }

            // Reorder calling args to parameter args
            methodHandle = MethodHandles.permuteArguments(methodHandle, callingType, reorderMap);

            // Return method handle
            return methodHandle;
        }
        catch (IllegalAccessException | NoSuchMethodException e)
        {
            // TODO: throw Invalid Invoker Exception
            if (!throwOnFailure)
            {
                return null;
            }

            throw new InvalidSignatureException("Unable to obtain MethodHandle for " + method, e);
        }
    }

    private static void appendTypeList(StringBuilder str, Arg[] args)
    {
        str.append("(");
        boolean comma = false;
        for (Arg arg : args)
        {
            if (comma)
                str.append(", ");
            str.append(arg.getType().getName());
            comma = true;
        }
        str.append(")");
    }

    private static void appendTypeList(StringBuilder str, Class<?>[] types)
    {
        str.append("(");
        boolean comma = false;
        for (Class<?> type : types)
        {
            if (comma)
                str.append(", ");
            str.append(type.getName());
            comma = true;
        }
        str.append(")");
    }
}
