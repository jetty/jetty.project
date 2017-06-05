//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import static java.util.stream.Collectors.toList;
import static org.eclipse.jetty.webapp.Configurations.getKnown;
import static org.hamcrest.Matchers.contains;
import static org.junit.Assert.assertThat;

import org.junit.Test;

public class ConfigurationsTest
{

    @Test
    public void testSetKnown()
    {
        Configurations.cleanKnown();
        Configurations.setKnown(
                ConfigBar.class.getName(),
                ConfigZ.class.getName(),
                ConfigY.class.getName(),
                ConfigX.class.getName(),
                ConfigTom.class.getName(),
                ConfigDick.class.getName(),
                ConfigHarry.class.getName(),
                ConfigAdditionalHarry.class.getName(),
                ConfigExtendedDick.class.getName(),
                ConfigFoo.class.getName()
        );
        
        assertThat(getKnown().stream().map(c->c.getClass().getName()).collect(toList()),
                contains(
                        ConfigFoo.class.getName(),
                        ConfigBar.class.getName(),
                        ConfigX.class.getName(),
                        ConfigY.class.getName(),
                        ConfigZ.class.getName(),
                        ConfigTom.class.getName(),
                        ConfigDick.class.getName(),
                        ConfigExtendedDick.class.getName(),
                        ConfigHarry.class.getName(),
                        ConfigAdditionalHarry.class.getName()
                        ));
        
    }
    
    @Test
    public void testConfigurations()
    {
        Configurations.cleanKnown();
        Configurations.setKnown(
                ConfigBar.class.getName(),
                ConfigZ.class.getName(),
                ConfigY.class.getName(),
                ConfigX.class.getName(),
                ConfigTom.class.getName(),
                ConfigDick.class.getName(),
                ConfigHarry.class.getName(),
                ConfigAdditionalHarry.class.getName(),
                ConfigExtendedDick.class.getName(),
                ConfigFoo.class.getName()
        );
        
     
        
        Configurations configs = new Configurations(
                ConfigBar.class.getName(),
                ConfigZ.class.getName(),
                ConfigY.class.getName(),
                ConfigX.class.getName(),
                ConfigTom.class.getName(),
                ConfigDick.class.getName(),
                ConfigHarry.class.getName(),
                ConfigAdditionalHarry.class.getName(),
                ConfigFoo.class.getName()
        );
        
        configs.add(ConfigExtendedDick.class.getName());
        
        configs.sort();
                
        assertThat(configs.stream().map(c->c.getClass().getName()).collect(toList()),
                contains(
                        ConfigFoo.class.getName(),
                        ConfigBar.class.getName(),
                        ConfigX.class.getName(),
                        ConfigY.class.getName(),
                        ConfigZ.class.getName(),
                        ConfigTom.class.getName(),
                        ConfigExtendedDick.class.getName(),
                        ConfigHarry.class.getName(),
                        ConfigAdditionalHarry.class.getName()
                        ));
    }
    
    
    public static class ConfigFoo extends AbstractConfiguration 
    { 
        {
            addDependents(ConfigBar.class); }
    }
    
    public static class ConfigBar extends AbstractConfiguration 
    { 
    }
    
    public static class ConfigX extends AbstractConfiguration 
    { 
        {
            addDependencies(ConfigBar.class);
        }
    }
    public static class ConfigY extends AbstractConfiguration 
    { 
        {
            addDependencies(ConfigX.class);
            addDependents(ConfigZ.class);
        }
    }
    public static class ConfigZ extends AbstractConfiguration 
    { 
    }

    public static class ConfigTom extends AbstractConfiguration 
    { 
    }
    
    public static class ConfigDick extends AbstractConfiguration 
    { 
        {
            addDependencies(ConfigTom.class);
        }
    }
    
    public static class ConfigHarry extends AbstractConfiguration 
    { 
        {
            addDependencies(ConfigDick.class);
        }
    }

    public static class ConfigExtendedDick extends ConfigDick 
    { 
        {
            addDependencies(ConfigTom.class);
        }

        @Override
        public Class<? extends Configuration> replaces()
        {
            return ConfigDick.class;
        }
        
    }

    public static class ConfigAdditionalHarry extends ConfigHarry 
    { 
    }
    
    
    
    
    
}
