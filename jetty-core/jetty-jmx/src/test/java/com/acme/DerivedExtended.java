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

@ManagedObject(value = "Test the mbean extended stuff")
public class DerivedExtended extends Derived
{
    private String doodle4 = "doodle4";

    @ManagedAttribute(value = "The doodle4 name of something", name = "doodle4", setter = "setDoodle4")
    public String getDoodle4()
    {
        throw new IllegalAccessError();
    }

    public void setDoodle4(String doodle4)
    {
        this.doodle4 = doodle4;
    }

    @ManagedOperation("Doodle2 something")
    private void doodle2()
    {
        System.err.println("doodle2");
        // this is just for a test case perspective
    }

    @ManagedOperation("Doodle1 something")
    public void doodle1()
    {
        // this is just for a test case perspective
        throw new IllegalAccessError();
    }
}
