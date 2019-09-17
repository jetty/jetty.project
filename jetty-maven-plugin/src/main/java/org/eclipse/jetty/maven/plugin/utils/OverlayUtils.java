//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.maven.plugin.utils;

import java.util.ArrayList;

import com.google.common.base.Strings;
import org.eclipse.jetty.maven.plugin.OverlayConfig;

public final class OverlayUtils
{
    private OverlayUtils()
    {

    }

    public static OverlayConfig mergeOverlayConfig(OverlayConfig parent, OverlayConfig child)
    {
        OverlayConfig config = new OverlayConfig();
        config.setTargetPath(parent.getTargetPath());
        if (child.getTargetPath() != null)
        {
            if (config.getTargetPath() != null)
            {
                config.setTargetPath(config.getTargetPath() + "/");
            }
            config.setTargetPath(Strings.nullToEmpty(config.getTargetPath()) + child.getTargetPath());
        }
        if (parent.getIncludes() != null || child.getIncludes() != null)
        {
            config.setIncludes(new ArrayList<>());
            config.getIncludes().addAll(CollectionUtils.nullToEmpty(parent.getIncludes()));
            config.getIncludes().addAll(CollectionUtils.nullToEmpty(child.getIncludes()));
        }
        if (parent.getExcludes() != null || child.getExcludes() != null)
        {
            config.setExcludes(new ArrayList<>());
            config.getExcludes().addAll(CollectionUtils.nullToEmpty(parent.getExcludes()));
            config.getExcludes().addAll(CollectionUtils.nullToEmpty(child.getExcludes()));
        }
        config.setSkip(parent.isSkip() || child.isSkip());
        config.setFiltered(child.isFiltered());
        return config;
    }
}
