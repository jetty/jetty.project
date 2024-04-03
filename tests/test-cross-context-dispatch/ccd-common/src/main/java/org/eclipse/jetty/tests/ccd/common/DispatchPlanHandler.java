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

package org.eclipse.jetty.tests.ccd.common;

import java.nio.file.Path;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

public class DispatchPlanHandler extends Handler.Wrapper
{
    private Path plansDir;

    public Path getPlansDir()
    {
        return plansDir;
    }

    public void setPlansDir(Path plansDir)
    {
        this.plansDir = plansDir;
    }

    public void setPlansDir(String plansDir)
    {
        this.setPlansDir(Path.of(plansDir));
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        DispatchPlan dispatchPlan = (DispatchPlan)request.getAttribute(DispatchPlan.class.getName());

        if (dispatchPlan == null)
        {
            String planName = request.getHeaders().get("X-DispatchPlan");
            if (planName == null)
                callback.failed(new RuntimeException("Unable to find X-DispatchPlan"));
            Path planPath = plansDir.resolve(planName);
            dispatchPlan = DispatchPlan.read(planPath);
            dispatchPlan.addEvent("Initial plan: %s", planName);
            request.setAttribute(DispatchPlan.class.getName(), dispatchPlan);
        }

        dispatchPlan.addEvent("DispatchPlanHandler.handle() method=%s path-query=%s", request.getMethod(), request.getHttpURI().getPathQuery());

        return super.handle(request, response, callback);
    }
}
