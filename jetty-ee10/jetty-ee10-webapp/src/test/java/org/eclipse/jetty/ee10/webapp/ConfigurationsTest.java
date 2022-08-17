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

package org.eclipse.jetty.ee10.webapp;

import org.eclipse.jetty.util.resource.FileSystemPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.util.stream.Collectors.toList;
import static org.eclipse.jetty.ee10.webapp.Configurations.getKnown;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

public class ConfigurationsTest
{
    @AfterEach
    public void tearDown()
    {
        Configurations.cleanKnown();
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @BeforeEach
    public void setup()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
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
        {
            addDependents(ConfigBar.class);
        }
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
