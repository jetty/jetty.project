//----------------------------------------------------------------------
//
// Silly / Pointless Javascript to test GZIP compression.
//
//----------------------------------------------------------------------

var LOGO = {
  dat: [
    0x50, 0x89, 0x47, 0x4e, 0x0a, 0x0d, 0x0a, 0x1a, 0x00, 0x00, 0x0d, 0x00, 0x48, 0x49, 0x52, 0x44,
    0x00, 0x00, 0x45, 0x49, 0x44, 0x4e, 0x42, 0xae, 0x82, 0x60,
  ],
  disp: function()
  {
    // Do Nothing

    throw "Does Nothing!";
  }

};

try
{
  LOGO.disp();
}
catch(e)
{
  alert("Error: " + e + "\n");
}

