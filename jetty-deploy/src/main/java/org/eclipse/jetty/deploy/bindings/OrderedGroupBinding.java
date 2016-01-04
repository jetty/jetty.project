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

package org.eclipse.jetty.deploy.bindings;

import java.util.LinkedList;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppLifeCycle;
import org.eclipse.jetty.deploy.graph.Node;

/**
 * Provides a way of forcing the ordered execution of bindings within 
 * a declared binding target.
 * 
 */
public class OrderedGroupBinding implements AppLifeCycle.Binding
{
    private String[] _bindingTargets;
    
    private LinkedList<AppLifeCycle.Binding> _orderedBindings;
    
    public OrderedGroupBinding( String[] bindingTargets )
    { 
        _bindingTargets = bindingTargets;
    }
    
    public void addBinding(AppLifeCycle.Binding binding)
    {
        if ( _orderedBindings == null )
         {
            _orderedBindings = new LinkedList<AppLifeCycle.Binding>();
         }   
        
        _orderedBindings.add(binding);
    }
    
    public void addBindings(AppLifeCycle.Binding[] bindings)
    {
        if ( _orderedBindings == null )
        {
           _orderedBindings = new LinkedList<AppLifeCycle.Binding>();
        }
        
        for (AppLifeCycle.Binding binding : bindings)
        {
            _orderedBindings.add(binding);
        }
    }
     
    public String[] getBindingTargets()
    {
        return _bindingTargets;
    }

    public void processBinding(Node node, App app) throws Exception
    {
        for ( AppLifeCycle.Binding binding : _orderedBindings )
        {
            binding.processBinding(node,app);
        }
    }
}
