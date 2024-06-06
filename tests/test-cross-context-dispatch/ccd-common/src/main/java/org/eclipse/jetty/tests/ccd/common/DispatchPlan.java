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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

import org.eclipse.jetty.tests.ccd.common.steps.ContextRedispatchStep;
import org.eclipse.jetty.tests.ccd.common.steps.GetHttpSessionStep;
import org.eclipse.jetty.tests.ccd.common.steps.HttpRequestStep;
import org.eclipse.jetty.tests.ccd.common.steps.RequestDispatchStep;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.ajax.JSONEnumConvertor;
import org.eclipse.jetty.util.ajax.JSONPojoConvertor;

public class DispatchPlan
{
    private final Deque<Step> steps = new LinkedBlockingDeque<>();
    private final List<String> events = new ArrayList<>();
    private final List<String> expectedEvents = new ArrayList<>();
    private final List<Property> expectedProperties = new ArrayList<>();
    private final List<String> expectedOutput = new ArrayList<>();
    private HttpRequestStep requestStep;
    private String id;
    private String expectedContentType;

    public DispatchPlan()
    {
    }

    private static JSON newJSON()
    {
        JSON json = new JSON();
        json.addConvertor(DispatchPlan.class, new JSONPojoConvertor(DispatchPlan.class));
        json.addConvertor(DispatchType.class, new JSONEnumConvertor());
        List<Class<?>> classes = List.of(
            ContextRedispatchStep.class,
            RequestDispatchStep.class,
            GetHttpSessionStep.class,
            HttpRequestStep.class,
            Property.class
        );
        for (Class<?> clazz : classes)
        {
            json.addConvertor(clazz, new JSONPojoConvertor(clazz));
        }
        return json;
    }

    public static DispatchPlan read(Path inputJson) throws IOException
    {
        JSON json = newJSON();
        String rawJson = Files.readString(inputJson, StandardCharsets.UTF_8);
        DispatchPlan plan = (DispatchPlan)json.fromJSON(rawJson);
        plan.id = inputJson.getFileName().toString();
        return plan;
    }

    public static void write(DispatchPlan plan, Path outputJson) throws IOException
    {
        JSON json = newJSON();
        String planJson = json.toJSON(plan);
        Files.writeString(outputJson, planJson, StandardCharsets.UTF_8);
    }

    public void addEvent(String format, Object... args)
    {
        events.add(String.format(format, args));
    }

    public void addExpectedEvent(String event)
    {
        expectedEvents.add(event);
    }

    public void addExpectedOutput(String output)
    {
        expectedOutput.add(output);
    }

    public void addExpectedProperty(String name, String value)
    {
        expectedProperties.add(new Property(name, value));
    }

    public void addExpectedProperty(Property property)
    {
        expectedProperties.add(property);
    }

    public void addStep(Step step)
    {
        steps.push(step);
    }

    public List<String> getEvents()
    {
        return events;
    }

    public String getExpectedContentType()
    {
        return expectedContentType;
    }

    public void setExpectedContentType(String expectedContentType)
    {
        this.expectedContentType = expectedContentType;
    }

    public List<String> getExpectedEvents()
    {
        return expectedEvents;
    }

    public void setExpectedEvents(String[] events)
    {
        expectedEvents.clear();
        expectedEvents.addAll(List.of(events));
    }

    public List<String> getExpectedOutput()
    {
        return expectedOutput;
    }

    public void setExpectedOutput(String[] output)
    {
        expectedOutput.clear();
        expectedOutput.addAll(List.of(output));
    }

    public List<Property> getExpectedProperties()
    {
        return expectedProperties;
    }

    public void setExpectedProperties(Property[] properties)
    {
        expectedProperties.clear();
        expectedProperties.addAll(List.of(properties));
    }

    public HttpRequestStep getRequestStep()
    {
        return requestStep;
    }

    public void setRequestStep(HttpRequestStep requestStep)
    {
        this.requestStep = requestStep;
    }

    public Deque<Step> getSteps()
    {
        return steps;
    }

    public void setSteps(Step[] stepArr)
    {
        steps.clear();
        for (Step step: stepArr)
            steps.add(step);
    }

    public String id()
    {
        return id;
    }

    public Step popStep()
    {
        return steps.pollFirst();
    }

    @Override
    public String toString()
    {
        return "DispatchPlan[id=" + id + "]";
    }
}
