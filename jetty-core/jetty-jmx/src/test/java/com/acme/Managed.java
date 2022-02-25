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

@ManagedObject(value = "Managed Object")
public class Managed
{
    String managed = "foo";

    @ManagedAttribute("Managed Attribute")
    public String getManaged()
    {
        return managed;
    }

    public void setManaged(String managed)
    {
        this.managed = managed;
    }

    public String bad()
    {
        return "bad";
    }
}
