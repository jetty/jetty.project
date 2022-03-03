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

package org.eclipse.jetty.ee9.webapp;

public enum Origin
{
    NotSet, WebXml, WebDefaults, WebOverride, WebFragment, Annotation, API;

    public static Origin of(Object o)
    {
        if (o == null)
            return null;
        if (o instanceof java.lang.annotation.Annotation)
            return Annotation;
        if (o instanceof FragmentDescriptor)
            return WebFragment;
        else if (o instanceof OverrideDescriptor)
            return WebOverride;
        else if (o instanceof DefaultsDescriptor)
            return WebDefaults;
        else if (o instanceof WebDescriptor)
            return WebXml;
        else
            return API;
    }
}