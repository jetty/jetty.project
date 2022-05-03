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
