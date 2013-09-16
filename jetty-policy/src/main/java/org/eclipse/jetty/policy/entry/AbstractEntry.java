//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.policy.entry;

import org.eclipse.jetty.policy.PolicyContext;
import org.eclipse.jetty.policy.PolicyException;

public abstract class AbstractEntry
{
    private boolean isDirty = false;
    private boolean isExpanded = false;
    
    public abstract void expand( PolicyContext context ) throws PolicyException;

    public boolean isDirty()
    {
        return isDirty;
    }

    public void setDirty( boolean isDirty )
    {
        this.isDirty = isDirty;
    }

    public boolean isExpanded()
    {
        return isExpanded;
    }

    public void setExpanded( boolean isExpanded )
    {
        this.isExpanded = isExpanded;
    }
    
    
}
