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

package org.eclipse.jetty.annotations.resources;

import jakarta.annotation.Resource;
import jakarta.annotation.Resources;

/**
 * ResourceB
 */
@Resources({
    @Resource(name = "peach", mappedName = "resA"),
    @Resource(name = "pear", mappedName = "resB")
})
public class ResourceB extends ResourceA
{
    @Resource(mappedName = "resB")
    private Integer f; //test no inheritance of private fields

    @Resource
    private Integer p = 8; //test no injection because no value

    //test no annotation
    public void z()
    {
        System.err.println("ResourceB.z");
    }
}
