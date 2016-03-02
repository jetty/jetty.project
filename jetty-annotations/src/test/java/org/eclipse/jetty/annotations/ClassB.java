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

package org.eclipse.jetty.annotations;



/**
 * ClassB
 *
 *
 */
@Sample(value=50)
@Multi({"do", "re", "mi"})
public class ClassB extends ClassA implements InterfaceD
{

    //test override of public scope method
    @Sample(value=51)
    @Multi({"fa", "so", "la"})
    public void a()
    {
       System.err.println("ClassB.public");
    }
    
    //test override of package scope method
    @Sample(value=52)
    void c()
    {
        System.err.println("ClassB.package");
    }
    
    public void l()
    {
        System.err.println("Overridden method l has no annotation");
    }
    
    
    //test no annotation
    public void z()
    {
        System.err.println("ClassB.z");
    }

}
