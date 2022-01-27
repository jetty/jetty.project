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
