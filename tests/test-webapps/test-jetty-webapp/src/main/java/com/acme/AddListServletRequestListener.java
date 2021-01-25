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

package com.acme;

import java.util.ArrayList;
import java.util.List;
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;

public final class AddListServletRequestListener
    implements ServletRequestListener
{

    public void requestDestroyed(ServletRequestEvent event)
    {
        List al = (List)event.getServletContext().getAttribute("arraylist");
        if (al != null)
        {
            event.getServletContext().removeAttribute("arraylist");
        }
    }

    public void requestInitialized(ServletRequestEvent event)
    {
        List al = (List)event.getServletContext().getAttribute("arraylist");
        if (al == null)
        {
            al = new ArrayList();
        }
        al.add("in requestInitialized method of " + getClass().getName());
        event.getServletContext().setAttribute("arraylist", al);
    }
}
