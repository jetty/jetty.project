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

package com.acme;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;

@ManagedObject(value = "Test the mbean stuff")
public class Derived extends Base implements Signature
{
    String fname = "Full Name";

    Managed managedInstance = new Managed();

    SuperManaged superManagedInstance = new SuperManaged();

    @ManagedAttribute(value = "The full name of something", name = "fname", setter = "setFullName")
    @Override
    public String getFullName()
    {
        return fname;
    }

    public void setFullName(String name)
    {
        fname = name;
    }

    @ManagedOperation("publish something")
    @Override
    public void publish()
    {
        System.err.println("publish");
    }

    @ManagedOperation("Doodle something")
    public void doodle(@Name(value = "doodle", description = "A description of the argument") String doodle)
    {
        System.err.println("doodle " + doodle);
    }

    public String bad()
    {
        return "bad";
    }

    @ManagedAttribute("sample managed object")
    public Managed getManagedInstance()
    {
        return managedInstance;
    }

    public void setManagedInstance(Managed managedInstance)
    {
        this.managedInstance = managedInstance;
    }

    @ManagedAttribute("sample super managed object")
    public SuperManaged getSuperManagedInstance()
    {
        return superManagedInstance;
    }
}
