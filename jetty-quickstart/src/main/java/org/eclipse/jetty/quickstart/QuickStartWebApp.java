//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

    private String _originAttribute;
    private boolean _generateOrigin;
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
        switch(_quickStartConfiguration.getMode())
        {
            case AUTO:
            case GENERATE:
                return true;
            default: return false;
        }
    }
    
    @Deprecated
    public void setPreconfigure(boolean preconfigure)
    {
        _quickStartConfiguration.setMode(preconfigure?Mode.GENERATE:Mode.DISABLED);
    }

    @Deprecated
    public boolean isAutoPreconfigure()
    {
        switch(_quickStartConfiguration.getMode())
        {
            case AUTO: return true;
            default: return false;
        }
    }

    @Deprecated
    public void setAutoPreconfigure(boolean autoPrecompile)
    {
        _quickStartConfiguration.setMode(autoPrecompile?Mode.AUTO:Mode.DISABLED);
    }

    public void setOriginAttribute (String name)
    {
        setAttribute(QuickStartConfiguration.ORIGIN_ATTRIBUTE,name);
    }

    /**
     * @return the originAttribute
     */
    public String getOriginAttribute()
    {
        Object attr = getAttribute(QuickStartConfiguration.ORIGIN_ATTRIBUTE);
        return attr==null?null:attr.toString();
    }

    /**
     * @param generateOrigin the generateOrigin to set
     */
    public void setGenerateOrigin(boolean generateOrigin)
    {
        setAttribute(QuickStartConfiguration.GENERATE_ORIGIN,generateOrigin);
    }

    /**
     * @return the generateOrigin
     */
    public boolean isGenerateOrigin()
    {
        Object attr = getAttribute(QuickStartConfiguration.GENERATE_ORIGIN);
        return attr==null?false:Boolean.valueOf(attr.toString());
    }

    public Mode getMode()
    {
        return _quickStartConfiguration.getMode();
    }

    public void setMode(Mode mode)
    {
        _quickStartConfiguration.setMode(mode);
    }
}
