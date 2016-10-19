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

package org.eclipse.jetty.quickstart;

import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.plus.webapp.EnvConfiguration;
import org.eclipse.jetty.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.quickstart.QuickStartConfiguration.Mode;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * QuickStartWar
 */
public class QuickStartWebApp extends WebAppContext
{
    private final QuickStartConfiguration _quickStartConfiguration;
    
    public QuickStartWebApp()
    {
        super();
        addConfiguration(
                _quickStartConfiguration=new QuickStartConfiguration(),
                new EnvConfiguration(),
                new PlusConfiguration(),
                new AnnotationConfiguration());
        setExtractWAR(true);
        setCopyWebDir(false);
        setCopyWebInf(false);
    }

    @Deprecated
    public boolean isPreconfigure()
    {
        return isGenerate();
    }
    
    @Deprecated
    public void setPreconfigure(boolean preconfigure)
    {
        setGenerate(preconfigure);
    }

    @Deprecated
    public boolean isAutoPreconfigure()
    {
        return isAutoGenerate();
    }

    @Deprecated
    public void setAutoPreconfigure(boolean autoPrecompile)
    {
        setAutoGenerate(autoPrecompile);
    }
    
    public boolean isGenerate()
    {
        return _quickStartConfiguration.getMode()==Mode.GENERATE;
    }
    
    public void setGenerate(boolean preconfigure)
    {
        _quickStartConfiguration.setMode(Mode.GENERATE);
    }
    
    public boolean isAutoGenerate()
    {
        return _quickStartConfiguration.getMode()==Mode.AUTO;
    }
    
    public void setAutoGenerate(boolean autoPrecompile)
    {
        _quickStartConfiguration.setMode(Mode.AUTO);
    }
}
