package io.github.alzeiby.anmjmorphplus;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Ground-truth binary-topology tests, executed on macOS with Fiji's JDK.
 * Synthetic ground truth for automatic cluster separation.
 */
public class AutomaticClusterSeparatorTest {
    static ByteProcessor mask(int w,int h) {
        ByteProcessor p=new ByteProcessor(w,h);
        p.setValue(255); p.fill();
        return p;
    }
    static void disk(ByteProcessor p,int cx,int cy,int r) {
        for (int y=0;y<p.getHeight();y++)for(int x=0;x<p.getWidth();x++)
            if((x-cx)*(x-cx)+(y-cy)*(y-cy)<=r*r) p.set(x,y,0);
    }
    static int count(ByteProcessor p) {
        return AnalysisWorkflow.countEndplateClusters(new ImagePlus("binary",AnalysisWorkflow.segmentEndplate(p)));
    }
    @Test public void singleObjectWithHoleRemainsOneCluster() {
        ByteProcessor p=mask(120,90);
        disk(p,60,45,32);
        for(int y=0;y<90;y++)for(int x=0;x<120;x++)
            if((x-60)*(x-60)+(y-45)*(y-45)<12*12) p.set(x,y,255);
        assertEquals(1,count(p));
    }
    @Test public void twoSeparatedObjectsAreTwoClusters() {
        ByteProcessor p=mask(120,90);
        disk(p,32,45,15);disk(p,86,45,15);
        assertEquals(2,count(p));
    }
    @Test public void twoTouchingDisksAreAutomaticallySeparated() {
        ByteProcessor p=mask(120,90);
        disk(p,40,45,23);disk(p,80,45,23);
        assertEquals(2,count(p));
    }
    @Test public void twoSlightlyOverlappingDisksAreAutomaticallySeparated() {
        ByteProcessor p=mask(120,90);
        disk(p,38,45,23);disk(p,82,45,23);
        assertEquals(2,count(p));
    }
    @Test public void longSolidBarIsNotArtificiallySplit() {
        ByteProcessor p=mask(120,90);
        for(int y=35;y<=55;y++)for(int x=15;x<=105;x++)p.set(x,y,0);
        assertEquals(1,count(p));
    }
    @Test public void narrowDumbbellSeparatesIntoTwoRegions() {
        ByteProcessor p=mask(120,90);
        disk(p,35,45,18);disk(p,85,45,18);
        for(int y=43;y<=47;y++)for(int x=35;x<=85;x++)p.set(x,y,0);
        assertEquals(2,count(p));
    }
    @Test public void diagonalTouchingLobesAreAutomaticallySeparated() {
        ByteProcessor p=mask(120,110);
        disk(p,43,36,23);disk(p,74,67,23);
        assertEquals(2,count(p));
    }
    @Test public void threeNeckedLobesAreThreeClusters() {
        ByteProcessor p=mask(125,95);
        disk(p,30,45,19);disk(p,62,45,19);disk(p,94,45,19);
        assertEquals(3,count(p));
    }
    @Test public void elongatedSmoothEllipseIsNotInventivelySplit() {
        ByteProcessor p=mask(125,95);
        for(int y=0;y<95;y++)for(int x=0;x<125;x++) {
            double xx=(x-62)/45.0, yy=(y-47)/15.0;
            if(xx*xx+yy*yy<=1)p.set(x,y,0);
        }
        assertEquals(1,count(p));
    }
    @Test public void automaticSplitNeverCreatesForegroundPixels() {
        ByteProcessor p=mask(120,90);
        disk(p,40,45,23);disk(p,80,45,23);
        ByteProcessor s=AnalysisWorkflow.segmentEndplate(p);
        for(int i=0;i<p.getPixelCount();i++)
            if(s.get(i)==255)assertEquals(0,p.get(i));
    }
    @Test public void emptyMaskHasNoClusters() {
        assertEquals(0,count(mask(100,80)));
    }
    @Test public void oneFilledBlobIsOneCluster() {
        ByteProcessor p=mask(120,90);disk(p,60,45,25);
        assertEquals(1,count(p));
    }
}
