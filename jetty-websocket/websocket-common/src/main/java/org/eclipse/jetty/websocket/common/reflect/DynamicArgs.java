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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Provide argument utilities for working with methods that
 * have a dynamic assortment of arguments.
 * <ol>
 * <li>Can identify a set of parameters as matching the Builder</li>
 * <li>Can create a DynamicArgs for the matched signature</li>
 * <li>Can create an argument array for the provided potential arguments,
 * suitable to be used with {@link Method#invoke(Object, Object...)}</li>
 * </ol>
 */
public class DynamicArgs
{
    public interface Signature
    {
        /**
         * Predicate to test if signature matches
         *
         * @return the predicate to test if signature matches
         */
        Predicate<Method> getPredicate();

        /**
         * Get Call Args
         *
         * @return the Call Args
         */
        Arg[] getCallArgs();

        /**
         * BiFunction to use to invoke method
         * against give object, with provided (potential) arguments,
         * returning appropriate result from invocation.
         *
         * @param method the method to base BiFunction off of.
         * @param callArgs the description of arguments passed into each {@link DynamicArgs#invoke(Object, Object...)}
         * call in the future. Used to map the incoming arguments to the method arguments.
         * @return the return result of the invoked method
         */
        BiFunction<Object, Object[], Object> getInvoker(Method method, Arg... callArgs);

        void appendDescription(StringBuilder str);
    }

    public static class Builder implements Predicate<Method>
    {
        private List<Signature> signatures = new ArrayList<>();

        public DynamicArgs build(Method method, Arg... callArgs)
        {
            Signature signature = getMatchingSignature(method);
            if (signature == null)
                return null;
            return build(method, signature);
        }

        public DynamicArgs build(Method method, Signature signature)
        {
            return new DynamicArgs(signature.getInvoker(method, signature.getCallArgs()));
        }

        @Override
        public boolean test(Method method)
        {
            return hasMatchingSignature(method);
        }

        /**
         * Used to identify a possible method signature match.
         *
         * @param method the method to test
         * @return true if it is a match
         */
        public boolean hasMatchingSignature(Method method)
        {
            return getMatchingSignature(method) != null;
        }

        /**
         * Get the {@link Signature} that matches the method
         *
         * @param method the method to inspect
         * @return the Signature, or null if no signature match
         */
        public Signature getMatchingSignature(Method method)
        {
            // FIXME: add match cache (key = method, value = signature)

            for (Signature sig : signatures)
            {
                if (sig.getPredicate().test(method))
                {
                    return sig;
                }
            }

            return null;
        }

        public Builder addSignature(Arg... args)
        {
            signatures.add(new UnorderedSignature(args));
            return this;
        }

        public void appendDescription(StringBuilder err)
        {
            for (Signature sig : signatures)
            {
                err.append(System.lineSeparator());
                sig.appendDescription(err);
            }
        }
    }

    private static List<ArgIdentifier> argIdentifiers;

    public static List<ArgIdentifier> lookupArgIdentifiers()
    {
        if (argIdentifiers == null)
        {
            ServiceLoader<ArgIdentifier> loader = ServiceLoader.load(ArgIdentifier.class);
            argIdentifiers = new ArrayList<>();
            for (ArgIdentifier argId : loader)
            {
                argIdentifiers.add(argId);
            }
        }

        return argIdentifiers;
    }

    /**
     * BiFunction invoker
     * <ol>
     * <li>First Arg</li>
     * <li>Second Arg</li>
     * <li>Result Type</li>
     * </ol>
     */
    private final BiFunction<Object, Object[], Object> invoker;

    private DynamicArgs(BiFunction<Object, Object[], Object> invoker)
    {
        this.invoker = invoker;
    }

    /**
     * Invoke the signature / method with the provided potential args.
     *
     * @param o the object to call method on
     * @param potentialArgs the potential args in the same order as the Call Args
     * @return the response object from the invoke
     */
    public Object invoke(Object o, Object... potentialArgs)
    {
        return invoker.apply(o, potentialArgs);
    }
}
