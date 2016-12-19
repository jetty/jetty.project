//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
    public String getFullName()
    {
        return fname;
    }

    public void setFullName(String name)
    {
        fname = name;
    }

    @ManagedOperation("publish something")
    public void publish()
    {
        System.err.println("publish");
    }
}
