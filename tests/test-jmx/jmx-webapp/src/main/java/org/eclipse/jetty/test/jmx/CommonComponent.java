//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.test.jmx;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

@ManagedObject("A common component available in the webapp")
public class CommonComponent extends AbstractLifeCycle
{
    private static final Logger LOG = Log.getLogger(CommonComponent.class);

    public CommonComponent()
    {
        LOG.info("Created " + this.getClass().getName());
    }

    private String name = "i am common";

    @ManagedAttribute("The name being tracked")
    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }
}
