out = getArgument();
if (out == "") exit("Usage: <output-path>");

newImage("source", "8-bit ramp", 64, 48, 2);
run("Properties...", "channels=2 slices=1 frames=1");
sourceId = getImageID();
selectImage(sourceId);
copyTitle = "__probe_segment_" + sourceId;
run("Duplicate...", "title=[" + copyTitle + "] duplicate");
copyId = getImageID();

newImage("decoy", "8-bit black", 16, 16, 1);
selectImage(copyId);
run("Arrange Channels...", "new=12");
selectImage(copyId);
Stack.setChannel(1);
run("Split Channels");

titles = getList("image.titles");
report = "copyId=" + copyId + "\n";
for (i=0; i<titles.length; i++) report = report + titles[i] + "\n";
File.saveString(report, out);
run("Close All");
