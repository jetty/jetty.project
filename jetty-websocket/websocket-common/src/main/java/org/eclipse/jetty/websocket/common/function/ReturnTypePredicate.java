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
import java.util.function.Predicate;

public class ReturnTypePredicate implements Predicate<Method>
{
    public static final Predicate<Method> VOID = new ReturnTypePredicate(Void.TYPE);

    private final Class<?> type;

    public ReturnTypePredicate(Class<?> type)
    {
        this.type = type;
    }

    @Override
    public boolean test(Method method)
    {
        return type.equals(method.getReturnType());
    }
}
