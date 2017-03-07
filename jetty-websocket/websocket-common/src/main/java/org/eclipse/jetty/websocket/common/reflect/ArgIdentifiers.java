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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;

public class ArgIdentifiers
{
    private static List<ArgIdentifier> argIdentifiers;
    
    public static List<ArgIdentifier> get()
    {
        if (argIdentifiers == null)
        {
            ServiceLoader<ArgIdentifier> loader = ServiceLoader.load(ArgIdentifier.class);
            List<ArgIdentifier> identifiers = new ArrayList<>();
            for (ArgIdentifier argId : loader)
            {
                identifiers.add(argId);
            }
            argIdentifiers = Collections.unmodifiableList(identifiers);
        }
        
        return argIdentifiers;
    }
}
