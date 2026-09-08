args = split(getArgument(), "|");
if (args.length != 2) exit("Usage: <image-path>|<output-path>");
path = args[0];
out = args[1];

run("Bio-Formats Importer", "open=[" + path + "] autoscale color_mode=Default view=Hyperstack stack_order=XYCZT");
getDimensions(w, h, c, z, t);
getPixelSize(unit, pw, ph);
report = "title=" + getTitle() + "\n" +
         "w=" + w + " h=" + h + " c=" + c + " z=" + z + " t=" + t + " bit=" + bitDepth() + "\n" +
         "pw=" + pw + " ph=" + ph + " unit=" + unit + "\n";
File.saveString(report, out);
run("Close All");
