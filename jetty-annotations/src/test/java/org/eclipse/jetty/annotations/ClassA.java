//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
 * ClassA
 */
@Sample(1)
public class ClassA
{
    private Integer e;
    private Integer f;
    private Integer g;
    private Integer h;
    private Integer j;
    private Integer k;

    public static class Foo
    {

    }

    @Sample(7)
    private Integer m;

    @Sample(2)
    public void a(Integer[] x)
    {
        System.err.println("ClassA.public");
    }

    @Sample(3)
    protected void b(Foo[] f)
    {
        System.err.println("ClassA.protected");
    }

    @Sample(4)
    void c(int[] x)
    {
        System.err.println("ClassA.package");
    }

    @Sample(5)
    private void d(int x, String y)
    {
        System.err.println("ClassA.private");
    }

    @Sample(6)
    protected void l()
    {
        System.err.println("ClassA.protected method l");
    }

    public Integer getE()
    {
        return this.e;
    }

    public Integer getF()
    {
        return this.f;
    }

    public Integer getG()
    {
        return this.g;
    }

    public Integer getJ()
    {
        return this.j;
    }

    public void x()
    {
        System.err.println("ClassA.x");
    }
}
