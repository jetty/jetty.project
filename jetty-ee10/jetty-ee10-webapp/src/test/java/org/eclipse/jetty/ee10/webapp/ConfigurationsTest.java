//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.webapp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import static java.util.stream.Collectors.toList;
import static org.eclipse.jetty.ee10.webapp.Configurations.getKnown;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@Isolated("Access static field of Configurations")
public class ConfigurationsTest
{
    @AfterEach
    public void tearDown()
    {
        Configurations.cleanKnown();
    }

    @BeforeEach
    public void setup()
    {
        Configurations.cleanKnown();
    }

    @Test
    public void testSetKnown()
    {
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

        assertThat(getKnown().stream().map(c -> c.getClass().getName()).collect(toList()),
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

        assertThat(configs.stream().map(c -> c.getClass().getName()).collect(toList()),
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

    @Test
    public void testDuplicates()
    {
        Configurations.setKnown(
            ConfigBar.class.getName(),
            ConfigZ.class.getName()
        );

        Configurations configs = new Configurations(
            ConfigBar.class.getName(),
            ConfigZ.class.getName());
        configs.add(ConfigZ.class.getName());
        configs.sort();
        assertThat(configs.stream().map(c -> c.getClass().getName()).collect(toList()),
            contains(ConfigBar.class.getName(), ConfigZ.class.getName()));
        assertThat(configs.getConfigurations().size(), equalTo(2));
    }

    @Test
    public void testReplacementWithInstances()
    {
        //ConfigDick should be replaced by ReplacementDick
        Configurations configs = new Configurations(
            new ConfigDick(),
            new ConfigBar(),
            new ConfigZ(),
            new ConfigY(),
            new ConfigX(),
            new ConfigTom(),
            new ReplacementDick(),
            new ConfigHarry(),
            new ConfigAdditionalHarry(),
            new ConfigFoo()
        );

        configs.sort();

        assertThat(configs.stream().map(c -> c.getClass().getName()).collect(toList()),
            contains(
                ConfigFoo.class.getName(),
                ConfigBar.class.getName(),
                ConfigX.class.getName(),
                ConfigY.class.getName(),
                ConfigZ.class.getName(),
                ConfigTom.class.getName(),
                ReplacementDick.class.getName(),
                ConfigHarry.class.getName(),
                ConfigAdditionalHarry.class.getName()
            ));

         assertThat(configs.stream().map(c -> c.getClass().getName()).collect(toList()),
            not(contains(
                ConfigDick.class.getName()
            )));
    }
    
    @Test
    public void testReplacement()
    {
        Configurations.setKnown(
            ConfigBar.class.getName(),
            ConfigZ.class.getName(),
            ConfigY.class.getName(),
            ConfigX.class.getName(),
            ConfigTom.class.getName(),
            ConfigDick.class.getName(),
            ConfigHarry.class.getName(),
            ConfigAdditionalHarry.class.getName(),
            ConfigFoo.class.getName(),
            ReplacementDick.class.getName()
        );

        Configurations configs = new Configurations(
            ConfigBar.class.getName(),
            ConfigZ.class.getName(),
            ConfigY.class.getName(),
            ConfigX.class.getName(),
            ConfigTom.class.getName(),
            ReplacementDick.class.getName(),
            ConfigHarry.class.getName(),
            ConfigAdditionalHarry.class.getName(),
            ConfigFoo.class.getName()
        );

        //ReplacementDick is already in the list and thus ConfigDick should not be added
        configs.add(ConfigDick.class.getName());
        configs.sort();

        assertThat(configs.stream().map(c -> c.getClass().getName()).collect(toList()),
            contains(
                ConfigFoo.class.getName(),
                ConfigBar.class.getName(),
                ConfigX.class.getName(),
                ConfigY.class.getName(),
                ConfigZ.class.getName(),
                ConfigTom.class.getName(),
                ReplacementDick.class.getName(),
                ConfigHarry.class.getName(),
                ConfigAdditionalHarry.class.getName()
            ));

        assertThat(configs.stream().map(c -> c.getClass().getName()).collect(toList()),
            not(contains(
                ConfigDick.class.getName()
            )));
    }

    @Test
    public void testTransitiveReplacements() throws Exception
    {
        Configurations.setKnown(
            ConfigBar.class.getName(),
            ConfigZ.class.getName(),
            ConfigY.class.getName(),
            ConfigX.class.getName(),
            ConfigTom.class.getName(),
            ConfigDick.class.getName(),
            ConfigHarry.class.getName(),
            ConfigAdditionalHarry.class.getName(),
            ConfigFoo.class.getName(),
            ReplacementDick.class.getName(),
            AnotherReplacementDick.class.getName()
        );

        Configurations configs = new Configurations(
            ConfigBar.class.getName(),
            ConfigZ.class.getName(),
            ConfigY.class.getName(),
            ConfigX.class.getName(),
            ConfigTom.class.getName(),
            AnotherReplacementDick.class.getName(),
            ConfigDick.class.getName(),
            ConfigHarry.class.getName(),
            ConfigAdditionalHarry.class.getName(),
            ConfigFoo.class.getName()
        );

        configs.add(ReplacementDick.class.getName());
        configs.sort();

        assertThat(configs.stream().map(c -> c.getClass().getName()).collect(toList()),
            contains(
                ConfigFoo.class.getName(),
                ConfigBar.class.getName(),
                ConfigX.class.getName(),
                ConfigY.class.getName(),
                ConfigZ.class.getName(),
                ConfigTom.class.getName(),
                AnotherReplacementDick.class.getName(),
                ConfigHarry.class.getName(),
                ConfigAdditionalHarry.class.getName()
            ));

        assertThat(configs.stream().map(c -> c.getClass().getName()).collect(toList()),
            not(contains(
                ReplacementDick.class.getName(),
                ConfigDick.class.getName()
            )));
    }

    public static class ConfigFoo extends AbstractConfiguration
    {
        public ConfigFoo()
        {
            super(new Builder().addDependents(ConfigBar.class));
        }
    }

    public static class ConfigBar extends AbstractConfiguration
    {
        public ConfigBar()
        {
            super(new Builder());
        }
    }

    public static class ConfigX extends AbstractConfiguration
    {
        public ConfigX()
        {
            super(new Builder().addDependencies(ConfigBar.class));
        }
    }

    public static class ConfigY extends AbstractConfiguration
    {
        public ConfigY()
        {
            super(new Builder()
                .addDependencies(ConfigX.class)
                .addDependents(ConfigZ.class));
        }
    }

    public static class ConfigZ extends AbstractConfiguration
    {
        public ConfigZ()
        {
            super(new Builder());
        }
    }

    public static class ConfigTom extends AbstractConfiguration
    {
        public ConfigTom()
        {
            super(new Builder());
        }
    }

    public static class ConfigDick extends AbstractConfiguration
    {
        public ConfigDick()
        {
            this(new Builder());
        }

        public ConfigDick(Builder builder)
        {
            super(builder.addDependencies(ConfigTom.class));
        }
    }

    public static class ConfigHarry extends AbstractConfiguration
    {
        public ConfigHarry()
        {
            super(new Builder().addDependencies(ConfigDick.class));
        }
    }

    public static class ConfigExtendedDick extends ConfigDick
    {
        public ConfigExtendedDick()
        {
            super(new Builder().addDependencies(ConfigTom.class));
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

    public static class ReplacementDick extends ConfigDick
    {
        @Override
        public Class<? extends Configuration> replaces()
        {
            return ConfigDick.class;
        }
    }

    public static class AnotherReplacementDick extends ReplacementDick
    {
        @Override
        public Class<? extends Configuration> replaces()
        {
            return ReplacementDick.class;
        }
    }
}
