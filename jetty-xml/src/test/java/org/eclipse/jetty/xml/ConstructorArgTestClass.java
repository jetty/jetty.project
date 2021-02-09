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

package org.eclipse.jetty.xml;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 *
 */
public class ConstructorArgTestClass
{
    @SuppressWarnings("rawtypes")
    private List list;

    @SuppressWarnings("rawtypes")
    private ArrayList arrayList;

    @SuppressWarnings("rawtypes")
    private Set set;

    @SuppressWarnings("rawtypes")
    public ConstructorArgTestClass(LinkedList list)
    {
        // not supported yet
    }

    @SuppressWarnings("rawtypes")
    public ConstructorArgTestClass(ArrayList arrayList, List list)
    {
        this.arrayList = arrayList;
        this.list = list;
    }

    @SuppressWarnings("rawtypes")
    public ConstructorArgTestClass(ArrayList list)
    {
        this.list = list;
    }

    @SuppressWarnings("rawtypes")
    public ConstructorArgTestClass(Set set)
    {
        this.set = set;
    }

    @SuppressWarnings("rawtypes")
    public List getList()
    {
        return list;
    }

    @SuppressWarnings("rawtypes")
    public ArrayList getArrayList()
    {
        return arrayList;
    }

    @SuppressWarnings("rawtypes")
    public Set getSet()
    {
        return set;
    }
}
