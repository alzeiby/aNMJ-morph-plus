tracePath = getArgument();
if (tracePath == "") {
  exit("Error: Java plugin smoke trace path is required");
}

newImage("java-time-series-smoke", "8-bit black", 16, 12, 2);
run("Properties...", "channels=1 slices=1 frames=2");
run("aNMJ-morph+");

File.saveString("DONE java plugin smoke\n", tracePath);
close();
