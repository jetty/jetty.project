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

package org.eclipse.jetty.test.session;

import java.io.Serializable;

public class Chocolate implements Serializable
{
    private static final long serialVersionUID = 1L;

    private static final String THE_BEST_EVER = "FRENCH";

    private String theBest = THE_BEST_EVER;

    public String getTheBest()
    {
        return this.theBest;
    }
}
