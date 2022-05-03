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

package org.eclipse.jetty.ee10.ant.types;

import java.util.ArrayList;
import java.util.List;

public class Attributes
{

    List<Attribute> _attributes = new ArrayList<Attribute>();

    public void addAttribute(Attribute attr)
    {
        _attributes.add(attr);
    }

    public List<Attribute> getAttributes()
    {
        return _attributes;
    }
}
