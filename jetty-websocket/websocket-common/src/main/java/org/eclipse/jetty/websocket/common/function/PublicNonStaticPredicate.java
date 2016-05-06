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

package org.eclipse.jetty.websocket.common.function;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Predicate;

public class PublicNonStaticPredicate implements Predicate<Method>
{
    public static final Predicate<Method> INSTANCE = new PublicNonStaticPredicate();

    @Override
    public boolean test(Method method)
    {
        int mods = method.getModifiers();
        if (!Modifier.isPublic(mods))
        {
            return false;
        }

        if (Modifier.isStatic(mods))
        {
            return false;
        }
        return true;
    }
}
