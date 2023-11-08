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

package org.eclipse.jetty.util;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

public class AttributesTest
{
    public static Stream<Attributes> attributes()
    {
        return Stream.of(
            new Attributes.Mapped(),
            new Attributes.Wrapper(new Attributes.Mapped()),
            new Attributes.Layer(new Attributes.Mapped()),
            new TestSynthetic(new Attributes.Mapped()),
            new AttributesMap()
        );
    }

    @ParameterizedTest
    @MethodSource("attributes")
    public void testAttributes(Attributes attributes)
    {
        assertThat(attributes.getAttributeNameSet(), empty());
        assertThat(attributes.removeAttribute("A"), nullValue());

        assertThat(attributes.setAttribute("A", "0"), nullValue());
        assertThat(attributes.getAttributeNameSet(), hasSize(1));
        assertThat(attributes.getAttributeNameSet(), containsInAnyOrder("A"));
        assertThat(attributes.getAttribute("A"), equalTo("0"));
        assertThat(attributes.getAttribute("B"), nullValue());

        assertThat(attributes.setAttribute("A", "1"), equalTo("0"));
        assertThat(attributes.getAttributeNameSet(), hasSize(1));
        assertThat(attributes.getAttributeNameSet(), containsInAnyOrder("A"));
        assertThat(attributes.getAttribute("A"), equalTo("1"));
        assertThat(attributes.getAttribute("B"), nullValue());

        assertThat(attributes.setAttribute("B", "2"), nullValue());
        assertThat(attributes.getAttributeNameSet(), hasSize(2));
        assertThat(attributes.getAttributeNameSet(), containsInAnyOrder("A", "B"));
        assertThat(attributes.getAttribute("A"), equalTo("1"));
        assertThat(attributes.getAttribute("B"), equalTo("2"));
        assertThat(attributes.getAttribute("C"), nullValue());

        assertThat(attributes.setAttribute("C", "3"), nullValue());
        assertThat(attributes.getAttributeNameSet(), hasSize(3));
        assertThat(attributes.getAttributeNameSet(), containsInAnyOrder("A", "B", "C"));
        assertThat(attributes.getAttribute("A"), equalTo("1"));
        assertThat(attributes.getAttribute("B"), equalTo("2"));
        assertThat(attributes.getAttribute("C"), equalTo("3"));

        assertThat(attributes.removeAttribute("A"), equalTo("1"));
        assertThat(attributes.getAttributeNameSet(), hasSize(2));
        assertThat(attributes.getAttributeNameSet(), containsInAnyOrder("B", "C"));
        assertThat(attributes.getAttribute("A"), nullValue());
        assertThat(attributes.getAttribute("B"), equalTo("2"));
        assertThat(attributes.getAttribute("C"), equalTo("3"));

        assertThat(attributes.setAttribute("B", null), equalTo("2"));
        assertThat(attributes.getAttributeNameSet(), hasSize(1));
        assertThat(attributes.getAttributeNameSet(), containsInAnyOrder("C"));
        assertThat(attributes.getAttribute("A"), nullValue());
        assertThat(attributes.getAttribute("B"), nullValue());
        assertThat(attributes.getAttribute("C"), equalTo("3"));

        attributes.clearAttributes();
        assertThat(attributes.getAttributeNameSet(), hasSize(0));
        assertThat(attributes.getAttribute("A"), nullValue());
        assertThat(attributes.getAttribute("B"), nullValue());
        assertThat(attributes.getAttribute("C"), nullValue());
    }

    @Test
    public void testAttributeLayer()
    {
        Attributes.Mapped persistent = new Attributes.Mapped();
        Attributes.Layer layer = new Attributes.Layer(persistent);

        assertThat(persistent.getAttributeNameSet(), empty());
        assertThat(persistent.removeAttribute("A"), nullValue());
        assertThat(layer.getAttributeNameSet(), empty());
        assertThat(layer.removeAttribute("A"), nullValue());

        assertThat(persistent.setAttribute("A", "1"), nullValue());
        assertThat(persistent.setAttribute("B", "2"), nullValue());
        assertThat(persistent.setAttribute("C", "3"), nullValue());

        assertThat(persistent.getAttributeNameSet(), hasSize(3));
        assertThat(persistent.getAttributeNameSet(), containsInAnyOrder("A", "B", "C"));
        assertThat(persistent.getAttribute("A"), equalTo("1"));
        assertThat(persistent.getAttribute("B"), equalTo("2"));
        assertThat(persistent.getAttribute("C"), equalTo("3"));
        assertThat(layer.getAttributeNameSet(), hasSize(3));
        assertThat(layer.getAttributeNameSet(), containsInAnyOrder("A", "B", "C"));
        assertThat(persistent.getAttribute("A"), equalTo("1"));
        assertThat(persistent.getAttribute("B"), equalTo("2"));
        assertThat(persistent.getAttribute("C"), equalTo("3"));

        assertThat(layer.removeAttribute("A"), equalTo("1"));
        assertThat(layer.setAttribute("B", null), equalTo("2"));
        assertThat(layer.setAttribute("C", null), equalTo("3"));
        assertThat(persistent.getAttributeNameSet(), hasSize(3));
        assertThat(persistent.getAttributeNameSet(), containsInAnyOrder("A", "B", "C"));
        assertThat(persistent.getAttribute("A"), equalTo("1"));
        assertThat(persistent.getAttribute("B"), equalTo("2"));
        assertThat(persistent.getAttribute("C"), equalTo("3"));
        assertThat(layer.getAttributeNameSet(), hasSize(0));
        assertThat(layer.getAttributeNameSet(), containsInAnyOrder());
        assertThat(layer.getAttribute("A"), nullValue());
        assertThat(layer.getAttribute("B"), nullValue());
        assertThat(layer.getAttribute("C"), nullValue());

        testAttributes(layer);
    }

    @Test
    public void testSynthetic()
    {
        TestSynthetic attributes = new TestSynthetic(new Attributes.Mapped());
        assertThat(attributes.getAttributeNameSet(), empty());
        attributes._roy.set("Batty");
        attributes._leon.set("Kowalski");

        assertThat(attributes.getAttribute("Leon"), equalTo("Kowalski"));
        assertThat(attributes.getAttribute("Zhora"), nullValue());
        assertThat(attributes.getAttribute("Pris"), nullValue());
        assertThat(attributes.getAttribute("Roy"), equalTo("Batty"));
        assertThat(attributes.getAttribute("A"), nullValue());

        assertThat(attributes.getAttributeNameSet(), containsInAnyOrder("Roy", "Leon"));

        assertThat(attributes.setAttribute("A", "1"), nullValue());
        assertThat(attributes.setAttribute("Pris", "Unknown"), nullValue());

        assertThat(attributes.getAttribute("Leon"), equalTo("Kowalski"));
        assertThat(attributes.getAttribute("Pris"), equalTo("Unknown"));
        assertThat(attributes.getAttribute("Roy"), equalTo("Batty"));
        assertThat(attributes.getAttribute("A"), equalTo("1"));
        assertThat(attributes.getAttributeNameSet(), containsInAnyOrder("Roy", "Leon", "A", "Pris"));

        assertThat(attributes.setAttribute("Leon", "retired"), equalTo("Kowalski"));
        assertThat(attributes.setAttribute("Zhora", "retired"), nullValue());
        assertThat(attributes.setAttribute("A", "2"), equalTo("1"));

        assertThat(attributes.getAttribute("Leon"), equalTo("retired"));
        assertThat(attributes.getAttribute("Zhora"), equalTo("retired"));
        assertThat(attributes.getAttribute("Pris"), equalTo("Unknown"));
        assertThat(attributes.getAttribute("Roy"), equalTo("Batty"));
        assertThat(attributes.getAttribute("A"), equalTo("2"));
        assertThat(attributes.getAttributeNameSet(), containsInAnyOrder("Roy", "Leon", "A", "Pris", "Zhora"));

        assertThat(attributes.removeAttribute("Leon"), equalTo("retired"));
        assertThat(attributes.removeAttribute("Zhora"), equalTo("retired"));
        assertThat(attributes.removeAttribute("Pris"), equalTo("Unknown"));

        assertThat(attributes.getAttribute("Roy"), equalTo("Batty"));
        assertThat(attributes.getAttribute("A"), equalTo("2"));
        assertThat(attributes.getAttributeNameSet(), containsInAnyOrder("Roy", "A"));

        attributes.clearAttributes();
        assertThat(attributes.getAttributeNameSet(), empty());
    }

    private static class TestSynthetic extends Attributes.Synthetic
    {
        Set<String> _syntheticAttributes = Set.of("Roy", "Pris", "Zhora", "Leon");
        AtomicReference<Object> _roy = new AtomicReference<>();
        AtomicReference<Object> _pris = new AtomicReference<>();
        AtomicReference<Object> _zhora = new AtomicReference<>();
        AtomicReference<Object> _leon = new AtomicReference<>();

        public TestSynthetic(Attributes attributes)
        {
            super(attributes);
        }

        @Override
        protected Object getSyntheticAttribute(String name)
        {
            return switch (name)
            {
                case "Roy" -> _roy.get();
                case "Pris" -> _pris.get();
                case "Zhora" -> _zhora.get();
                case "Leon" -> _leon.get();
                default -> null;
            };
        }

        @Override
        protected Set<String> getSyntheticNameSet()
        {
            return _syntheticAttributes;
        }
    }
}
