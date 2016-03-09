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
