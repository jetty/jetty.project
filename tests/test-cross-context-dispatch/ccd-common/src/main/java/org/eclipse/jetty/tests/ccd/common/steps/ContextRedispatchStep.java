//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.tests.ccd.common.steps;

import org.eclipse.jetty.tests.ccd.common.DispatchType;
import org.eclipse.jetty.tests.ccd.common.Step;

public class ContextRedispatchStep implements Step
{
    private DispatchType dispatchType;
    private String contextPath;
    private String dispatchPath;

    public DispatchType getDispatchType()
    {
        return dispatchType;
    }

    public void setDispatchType(DispatchType dispatchType)
    {
        this.dispatchType = dispatchType;
    }

    public String getContextPath()
    {
        return contextPath;
    }

    public void setContextPath(String contextPath)
    {
        this.contextPath = contextPath;
    }

    public String getDispatchPath()
    {
        return dispatchPath;
    }

    public void setDispatchPath(String dispatchPath)
    {
        this.dispatchPath = dispatchPath;
    }
}
