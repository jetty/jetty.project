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

package org.eclipse.jetty.annotations.resources;
import javax.annotation.Resource;
import javax.annotation.Resources;

/**
 * ResourceB
 *
 *
 */
@Resources({
    @Resource(name="peach", mappedName="resA"),
    @Resource(name="pear", mappedName="resB")
})
public class ResourceB extends ResourceA
{
    @Resource(mappedName="resB")
    private Integer f;//test no inheritance of private fields
    
    @Resource
    private Integer p = new Integer(8); //test no injection because no value
    
    //test no annotation
    public void z()
    {
        System.err.println("ResourceB.z");
    }
}
