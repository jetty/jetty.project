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

import java.util.ArrayList;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;

@ManagedObject(value = "Test the mbean stuff")
public class DerivedManaged extends Base implements Signature
{
    String fname = "Full Name";
    Derived[] addresses = new Derived[3];
    ArrayList<Derived> aliasNames = new ArrayList<Derived>();
    Derived derived = new Derived();

    @ManagedAttribute("sample managed address object")
    public Derived[] getAddresses()
    {
        return addresses;
    }

    @ManagedAttribute("sample managed derived object")
    public Derived getDerived()
    {
        return derived;
    }

    public void setDerived(Derived derived)
    {
        this.derived = derived;
    }

    public void setAddresses(Derived[] addresses)
    {
        this.addresses = addresses;
    }

    @ManagedOperation(value = "Doodle something", impact = "ACTION_INFO")
    public void doodle(@Name(value = "doodle", description = "A description of the argument") String doodle)
    {
        System.err.println("doodle " + doodle);
    }

    @ManagedOperation(value = "google something", impact = "")
    public void google(@Name(value = "google", description = "A description of the argument") String google)
    {
        System.err.println("google " + google);
    }

    @ManagedAttribute("sample managed alias object")
    public ArrayList<Derived> getAliasNames()
    {
        return aliasNames;
    }

    public void setAliasNames(ArrayList<Derived> aliasNames)
    {
        this.aliasNames = aliasNames;
    }

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
}
