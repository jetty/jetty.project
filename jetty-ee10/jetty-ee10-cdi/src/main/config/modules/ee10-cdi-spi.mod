# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Configures Jetty to use the "CdiSpiDecorator" as the default CDI mode.
This mode uses the CDI SPI to integrate an arbitrary CDI implementation.

[environment]
ee10

[tag]
cdi

[provides]
cdi-mode

[depend]
ee10-cdi

[ini]
jetty.cdi.mode=CdiSpiDecorator
