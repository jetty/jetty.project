// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package com.acme;


public class Derived extends Base implements Signature
{
    String fname="Full Name";

    public String getFullName()
    {
        return fname;
    }

    public void setFullName(String name)
    {
        fname=name;
    }

    public void publish()
    {
        System.err.println("publish");
    }
    
    public void doodle(String doodle)
    {
        System.err.println("doodle "+doodle);
    }

    public void somethingElse()
    {
        
    }
}
