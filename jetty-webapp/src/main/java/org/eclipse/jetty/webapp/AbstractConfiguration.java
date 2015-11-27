//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.webapp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class AbstractConfiguration implements Configuration
{
    private final List<String> _after;
    private final List<String> _before;
    
    protected AbstractConfiguration()
    {
        _after=Collections.emptyList();
        _before=Collections.emptyList();
    }

    protected AbstractConfiguration(String[] before,String[] after)
    {
        _after=Collections.unmodifiableList(after==null?Collections.emptyList():Arrays.asList(after));
        _before=Collections.unmodifiableList(before==null?Collections.emptyList():Arrays.asList(before));
    }
    
    protected AbstractConfiguration(List<String> after,List<String> before)
    {
        _after=Collections.unmodifiableList(after==null?Collections.emptyList():new ArrayList<>(after));
        _before=Collections.unmodifiableList(before==null?Collections.emptyList():new ArrayList<>(before));
    }
    
    @Override
    public String getName()
    {
        return this.getClass().getName();
    }

    @Override
    public List<String> getAfterThis()
    {
        return _after;
    }

    @Override
    public List<String> getBeforeThis()
    {
        return _before;
    }

    public void preConfigure(WebAppContext context) throws Exception
    {
    }

    public void configure(WebAppContext context) throws Exception
    {
    }

    public void postConfigure(WebAppContext context) throws Exception
    {
    }

    public void deconfigure(WebAppContext context) throws Exception
    {
    }

    public void destroy(WebAppContext context) throws Exception
    {
    }

    public void cloneConfigure(WebAppContext template, WebAppContext context) throws Exception
    {
    }
}
