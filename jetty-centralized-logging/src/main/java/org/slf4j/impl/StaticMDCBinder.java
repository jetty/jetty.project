// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses.
// ========================================================================
package org.slf4j.impl;

import org.slf4j.helpers.NOPMakerAdapter;
import org.slf4j.spi.MDCAdapter;

/**
 * Standard entry point for Slf4J, used to configure the desired {@link MDCAdapter}.
 */
public class StaticMDCBinder
{
    public static final StaticMDCBinder SINGLETON = new StaticMDCBinder();

    private StaticMDCBinder()
    {
        /* prevent external instantiation */
    }

    public MDCAdapter getMDCA()
    {
        return new NOPMakerAdapter();
    }

    public String getMDCAdapterClassStr()
    {
        return NOPMakerAdapter.class.getName();
    }
}
