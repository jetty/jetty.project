# DO NOT EDIT - See: https://jetty.org/docs/9/startup-modules.html

[description]
Configures Jetty to use the "CdiSpiDecorator" that calls the CDI SPI
as the default CDI integration mode.

[tag]
cdi

[provides]
cdi-mode

[depend]
cdi

[ini]
jetty.cdi.mode=CdiSpiDecorator
