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

package org.eclipse.jetty.annotations;

/**
 * ClassB
 */
@Sample(value = 50)
@Multi({"do", "re", "mi"})
public class ClassB extends ClassA implements InterfaceD
{

    //test override of public scope method
    @Sample(value = 51)
    @Multi({"fa", "so", "la"})
    public void a()
    {
        System.err.println("ClassB.public");
    }

    //test override of package scope method
    @Sample(value = 52)
    void c()
    {
        System.err.println("ClassB.package");
    }

    @Override
    public void l()
    {
        System.err.println("Overridden method l has no annotation");
    }

    //test no annotation
    public void z()
    {
        System.err.println("ClassB.z");
    }
}
