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

import java.io.IOException;
import java.nio.file.Path;

import org.eclipse.jetty.tests.ccd.common.steps.ContextRedispatchStep;
import org.eclipse.jetty.tests.ccd.common.steps.HttpRequestStep;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(WorkDirExtension.class)
public class DispatchPlanJsonTest
{
    @Test
    public void testWriteJson(WorkDir workDir) throws IOException
    {
        Path outputDir = workDir.getEmptyPathDir();
        DispatchPlan plan = new DispatchPlan();

        ContextRedispatchStep step = new ContextRedispatchStep();
        step.setDispatchType(DispatchType.FORWARD);
        step.setContextPath("/foo");
        step.setDispatchPath("/bar");
        plan.addStep(step);

        HttpRequestStep httpRequestStep = new HttpRequestStep();
        httpRequestStep.setMethod("GET");
        httpRequestStep.setRequestPath("/foo/redispatch");
        plan.setRequestStep(httpRequestStep);

        plan.addExpectedEvent("Initial");
        plan.addExpectedEvent("Request Path /foo/redispatch");

        plan.addExpectedOutput("request.dispatcherType=FORWARD");
        plan.setExpectedContentType("text/plain");
        plan.addExpectedProperty("request.dispatcherType", "FORWARD");

        DispatchPlan.write(plan, outputDir.resolve("simple.json"));
    }

    @Test
    public void testReadJson() throws IOException
    {
        Path ee8PlanPath = MavenPaths.findTestResourceFile("ee8-self-plan.json");
        DispatchPlan plan = DispatchPlan.read(ee8PlanPath);
        assertNotNull(plan);

        HttpRequestStep httpRequestStep = plan.getRequestStep();
        assertThat(httpRequestStep.getMethod(), is("GET"));
        assertThat(httpRequestStep.getRequestPath(), is("/ccd-ee8/redispatch"));
        assertThat(httpRequestStep.getBody(), is(nullValue()));

        assertEquals(1, plan.getSteps().size());
        Step step = plan.popStep();

        assertThat(step, instanceOf(ContextRedispatchStep.class));
        ContextRedispatchStep contextRedispatchStep = (ContextRedispatchStep)step;
        assertThat(contextRedispatchStep.getDispatchType(), is(DispatchType.FORWARD));
        assertThat(contextRedispatchStep.getContextPath(), is("/ccd-ee8"));
        assertThat(contextRedispatchStep.getDispatchPath(), is("/dump/ee8"));

        assertThat(plan.getExpectedContentType(), is("text/x-java-properties; charset=utf-8"));

        assertThat(plan.getExpectedEvents().size(), is(3));
        assertThat(plan.getExpectedOutput().size(), is(1));
        assertThat(plan.getExpectedProperties().size(), is(1));
    }
}
