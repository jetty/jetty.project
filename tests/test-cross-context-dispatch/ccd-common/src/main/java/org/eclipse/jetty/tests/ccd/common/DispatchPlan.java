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

public class DispatchPlan
{
    private final Deque<Step> steps = new LinkedBlockingDeque<>();
    private final List<String> events = new ArrayList<>();
    private final List<String> expectedEvents = new ArrayList<>();
    private final List<Property> expectedProperties = new ArrayList<>();
    private final List<String> expectedOutput = new ArrayList<>();
    private HttpRequest requestStep;
    private String id;
    private String expectedContentType;

    public DispatchPlan()
    {
    }

    public static DispatchPlan read(Path inputText) throws IOException
    {
        DispatchPlan plan = new DispatchPlan();

        plan.id = inputText.getFileName().toString();

        for (String line : Files.readAllLines(inputText, StandardCharsets.UTF_8))
        {
            if (line.startsWith("#"))
                continue; // skip
            if (line.startsWith("REQUEST|"))
            {
                plan.setRequestStep(HttpRequest.parse(line));
            }
            else if (line.startsWith("STEP|"))
            {
                plan.addStep(Step.parse(line));
            }
            else if (line.startsWith("EXPECTED_CONTENT_TYPE|"))
            {
                plan.setExpectedContentType(dropType(line));
            }
            else if (line.startsWith("EXPECTED_EVENT|"))
            {
                plan.addExpectedEvent(dropType(line));
            }
            else if (line.startsWith("EXPECTED_PROP|"))
            {
                plan.addExpectedProperty(Property.parse(line));
            }
            else if (line.startsWith("EXPECTED_OUTPUT|"))
            {
                plan.addExpectedOutput(dropType(line));
            }
        }
        return plan;
    }

    private static String dropType(String line)
    {
        int idx = line.indexOf("|");
        return line.substring(idx + 1);
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
        steps.add(step);
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

    public HttpRequest getRequestStep()
    {
        return requestStep;
    }

    public void setRequestStep(HttpRequest requestStep)
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
