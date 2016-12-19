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

package org.eclipse.jetty.webapp;

import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlParser;

public abstract class Descriptor
{
    protected Resource _xml;
    protected XmlParser.Node _root;
    protected String _dtd;
    protected boolean _validating;
    
    public Descriptor (Resource xml)
    {
        _xml = xml;
    }
    
    public abstract XmlParser ensureParser()
    throws ClassNotFoundException;
    
    
    public void setValidating (boolean validating)
    {
       _validating = validating;
    }
    
    public void parse ()
    throws Exception
    {

        
        if (_root == null)
        {
            try
            {
                XmlParser parser = ensureParser();
                _root = parser.parse(_xml.getInputStream());
                _dtd = parser.getDTD();
            }
            finally
            {
                _xml.close();
            }
        }
    }
    
    public Resource getResource ()
    {
        return _xml;
    }
    
    public XmlParser.Node getRoot ()
    {
        return _root;
    }
    
    public String toString()
    {
        return this.getClass().getSimpleName()+"("+_xml+")";
    }
}
