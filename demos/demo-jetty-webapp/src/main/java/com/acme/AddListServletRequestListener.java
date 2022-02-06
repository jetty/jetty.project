//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
