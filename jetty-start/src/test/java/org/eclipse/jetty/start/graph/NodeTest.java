//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.start.graph;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import org.junit.Test;

public class NodeTest
{
    private static class TestNode extends Node<TestNode>
    {
        public TestNode(String name)
        {
            setName(name);
        }

        @Override
        public String toString()
        {
            return String.format("TestNode[%s]",getName());
        }
    }

    @Test
    public void testNoNameMatch()
    {
        TestNode node = new TestNode("a");
        Predicate predicate = new NamePredicate("b");
        assertThat(node.toString(),node.matches(predicate),is(false));
    }
    
    @Test
    public void testNameMatch()
    {
        TestNode node = new TestNode("a");
        Predicate predicate = new NamePredicate("a");
        assertThat(node.toString(),node.matches(predicate),is(true));
    }
    
    @Test
    public void testAnySelectionMatch()
    {
        TestNode node = new TestNode("a");
        node.addSelection(new Selection("test"));
        Predicate predicate = new AnySelectionPredicate();
        assertThat(node.toString(),node.matches(predicate),is(true));
    }
    
    @Test
    public void testAnySelectionNoMatch()
    {
        TestNode node = new TestNode("a");
        // NOT Selected - node.addSelection(new Selection("test"));
        Predicate predicate = new AnySelectionPredicate();
        assertThat(node.toString(),node.matches(predicate),is(false));
    }
}
