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

import org.eclipse.jetty.util.annotation.Name;

/**
 * Arg Identifier based on jetty's {@link org.eclipse.jetty.util.annotation.Name} annotation.
 */
public class NameArgIdentifier implements ArgIdentifier
{
    @Override
    public Arg apply(Arg arg)
    {
        Name name = arg.getAnnotation(Name.class);
        if (name != null)
            arg.setTag(name.value());
        return arg;
    }
}
