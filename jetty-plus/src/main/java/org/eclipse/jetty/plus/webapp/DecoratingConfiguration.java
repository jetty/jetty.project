//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.plus.webapp;

import org.eclipse.jetty.webapp.AbstractConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;

public class DecoratingConfiguration extends AbstractConfiguration
{
    private final String _attributeName;

    public DecoratingConfiguration()
    {
        this("org.eclipse.jetty.plus.webapp.Decorator");
    }

    public DecoratingConfiguration(String attributeName)
    {
        super(true);
        _attributeName = attributeName;
    }

    @Override
    public void preConfigure(WebAppContext context)
    {
        context.addEventListener(new DecoratingListener(context, _attributeName));
    }
}
