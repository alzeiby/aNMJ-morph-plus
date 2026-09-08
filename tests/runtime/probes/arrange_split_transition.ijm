args = split(getArgument(), "|");
if (args.length != 2) exit("Usage: <image-path>|<output-path>");
path = args[0];
out = args[1];

run("Bio-Formats Importer", "open=[" + path + "] autoscale color_mode=Default view=Hyperstack stack_order=XYCZT");
imageId = getImageID();
selectImage(imageId);
run("Arrange Channels...", "new=12");
selectImage(imageId);
Stack.setChannel(1);
run("Split Channels");
titles = getList("image.titles");
report = "imageId=" + imageId + "\n";
for (i=0; i<titles.length; i++) report = report + titles[i] + "\n";
File.saveString(report, out);
run("Close All");
