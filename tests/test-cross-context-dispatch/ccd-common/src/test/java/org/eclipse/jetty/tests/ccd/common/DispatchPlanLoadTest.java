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

import org.eclipse.jetty.toolchain.test.MavenPaths;
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
public class DispatchPlanLoadTest
{
    @Test
    public void testRead() throws IOException
    {
        Path planPath = MavenPaths.findTestResourceFile("forward-include-dump.txt");
        DispatchPlan plan = DispatchPlan.read(planPath);
        assertNotNull(plan);

        assertThat("plan.id", plan.id(), is(planPath.getFileName().toString()));

        HttpRequest httpRequestStep = plan.getRequestStep();
        assertThat(httpRequestStep.getMethod(), is("GET"));
        assertThat(httpRequestStep.getRequestPath(), is("/ccd-ee10/redispatch/ee10"));
        assertThat(httpRequestStep.getBody(), is(nullValue()));

        assertEquals(3, plan.getSteps().size());

        Step step = plan.popStep();
        assertThat(step, instanceOf(Step.ContextRedispatch.class));
        Step.ContextRedispatch contextRedispatchStep = (Step.ContextRedispatch)step;
        assertThat(contextRedispatchStep.getDispatchType(), is(DispatchType.FORWARD));
        assertThat(contextRedispatchStep.getContextPath(), is("/ccd-ee8"));
        assertThat(contextRedispatchStep.getDispatchPath(), is("/redispatch/ee8"));

        step = plan.popStep();
        assertThat(step, instanceOf(Step.ContextRedispatch.class));
        contextRedispatchStep = (Step.ContextRedispatch)step;
        assertThat(contextRedispatchStep.getDispatchType(), is(DispatchType.FORWARD));
        assertThat(contextRedispatchStep.getContextPath(), is("/ccd-ee9"));
        assertThat(contextRedispatchStep.getDispatchPath(), is("/redispatch/ee9"));

        step = plan.popStep();
        assertThat(step, instanceOf(Step.RequestDispatch.class));
        Step.RequestDispatch requestRedispatchStep = (Step.RequestDispatch)step;
        assertThat(requestRedispatchStep.getDispatchType(), is(DispatchType.INCLUDE));
        assertThat(requestRedispatchStep.getDispatchPath(), is("/dump/ee9"));

        assertThat(plan.getExpectedContentType(), is("text/x-java-properties; charset=utf-8"));

        assertThat("Expected Events", plan.getExpectedEvents().size(), is(6));
        assertThat("Expected Output", plan.getExpectedOutput().size(), is(1));
        assertThat("Expected Properties", plan.getExpectedProperties().size(), is(18));
    }
}
