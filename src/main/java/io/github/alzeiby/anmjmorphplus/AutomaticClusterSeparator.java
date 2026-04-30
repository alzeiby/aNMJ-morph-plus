package io.github.alzeiby.anmjmorphplus;

import ij.ImagePlus;
import ij.plugin.filter.EDM;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Conservative, fully automatic separation of clearly necked AChR regions.
 *
 * Uses distance-to-background geometry, not intensity maxima of a binary mask.
 * Holes are preserved, and a split is accepted only if it creates exactly two
 * substantial connected regions. No Fiji watershed or global progress resets.
 */
final class AutomaticClusterSeparator {
    private static final int MAX_SPLIT_PASSES = 6;
    private static final double MIN_PEAK_RADIUS = 3.0; // 3 resolved pixels
    private static final double MAX_NECK_FRACTION = 0.72; // neck relative to both lobes

    private AutomaticClusterSeparator() {}

    static ByteProcessor segment(final ByteProcessor mask) {
        final ByteProcessor output=(ByteProcessor) mask.duplicate();
        output.resetThreshold();
        output.invert(); // Fiji's black endplate -> white foreground
        for (int pass=0; pass<MAX_SPLIT_PASSES; pass++) {
            final FloatProcessor distance=new EDM().makeFloatEDM(output,0,true);
            final List<Region> regions=regions(output);
            final int total=regions.size();
            boolean changed=false;
            for (Region region:regions) {
                if(region.pixels.size()<100 || hasHole(region,output)) continue;
                final List<Peak> peaks=peaks(region,distance,output);
                if(peaks.size()<2) continue;
                final Peak first=peaks.get(0);
                for(int j=1;j<Math.min(peaks.size(),12);j++) {
                    final Peak second=peaks.get(j);
                    if(second.r<MIN_PEAK_RADIUS ||
                        Math.hypot(first.x-second.x,first.y-second.y)<0.75*(first.r+second.r)) continue;
                    if(!hasNarrowNeck(first,second,distance,output)) continue;
                    final ByteProcessor candidate=(ByteProcessor)output.duplicate();
                    final double dx=second.x-first.x,dy=second.y-first.y;
                    final double length=Math.hypot(dx,dy);
                    final double mx=(first.x+second.x)*0.5,my=(first.y+second.y)*0.5;
                    int left=0,right=0;
                    for(int index:region.pixels) {
                        final int x=index%region.width,y=index/region.width;
                        final double signed=((x-mx)*dx+(y-my)*dy)/length;
                        if(signed<0)left++;else right++;
                        if(Math.abs(signed)<=0.9)candidate.set(index,0);
                    }
                    if(Math.min(left,right)<Math.max(12,region.pixels.size()/10))continue;
                    // Verify on the actual 8-connected result. A cut must
                    // introduce precisely one separation, no extra fragments.
                    if(regions(candidate).size()!=total+1)continue;
                    System.arraycopy(candidate.getPixels(),0,output.getPixels(),0,region.width*region.height);
                    changed=true;
                    break;
                }
                if(changed)break;
            }
            if(!changed)break;
        }
        return output;
    }

    private static boolean hasNarrowNeck(Peak a,Peak b,FloatProcessor d,ByteProcessor mask) {
        double narrow=Double.POSITIVE_INFINITY;
        // Probe between the inner portions of both lobes; a large continuous
        // bar should retain constant thickness and not be split.
        for (int i=3;i<=7;i++) {
            final double t=i/10.0;
            final int x=(int)Math.round(a.x+(b.x-a.x)*t);
            final int y=(int)Math.round(a.y+(b.y-a.y)*t);
            if(x<0||y<0||x>=mask.getWidth()||y>=mask.getHeight()||mask.get(x,y)==0)
                return false;
            narrow=Math.min(narrow,d.getf(x,y));
        }
        return narrow<MAX_NECK_FRACTION*Math.min(a.r,b.r);
    }

    private static List<Peak> peaks(Region region,FloatProcessor distance,ByteProcessor mask) {
        final List<Peak> maxima=new ArrayList<>();
        final int w=region.width,h=region.height;
        for(int idx:region.pixels) {
            final int x=idx%w,y=idx/w;
            final double value=distance.getf(x,y);
            if(value<MIN_PEAK_RADIUS)continue;
            boolean local=true;
            for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1)&&local;yy++)
                for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++)
                    if(distance.getf(xx,yy)>value+1e-6){local=false;break;}
            if(local)maxima.add(new Peak(x,y,value));
        }
        maxima.sort(Comparator.comparingDouble((Peak p)->p.r).reversed());
        final List<Peak> selected=new ArrayList<>();
        for(Peak p:maxima) {
            boolean tooNear=false;
            for(Peak previous:selected)
                if(Math.hypot(p.x-previous.x,p.y-previous.y)<Math.max(3,0.6*(p.r+previous.r))) {
                    tooNear=true;break;
                }
            if(!tooNear)selected.add(p);
            if(selected.size()>=16)break;
        }
        return selected;
    }

    private static List<Region> regions(ByteProcessor p) {
        final int w=p.getWidth(),h=p.getHeight(),n=w*h;
        final boolean[] visited=new boolean[n];
        final int[] fifo=new int[n];
        final List<Region> regions=new ArrayList<>();
        for(int seed=0;seed<n;seed++) {
            if(p.get(seed)!=255||visited[seed])continue;
            final Region r=new Region(w,h);
            visited[seed]=true;
            fifo[0]=seed;
            int front=0,end=1;
            while(front<end) {
                final int i=fifo[front++];
                r.pixels.add(i);
                final int x=i%w,y=i/w;
                r.minX=Math.min(r.minX,x);r.maxX=Math.max(r.maxX,x);
                r.minY=Math.min(r.minY,y);r.maxY=Math.max(r.maxY,y);
                for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++)
                    for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++) {
                        final int j=yy*w+xx;
                        if(!visited[j]&&p.get(j)==255) {
                            visited[j]=true;fifo[end++]=j;
                        }
                    }
            }
            regions.add(r);
        }
        return regions;
    }

    private static boolean hasHole(Region r,ByteProcessor mask) {
        // Flood background from the boundary of the region's expanded bbox.
        final int x0=Math.max(0,r.minX-1),x1=Math.min(r.width-1,r.maxX+1);
        final int y0=Math.max(0,r.minY-1),y1=Math.min(r.height-1,r.maxY+1);
        final int width=x1-x0+1,height=y1-y0+1;
        final boolean[] seen=new boolean[width*height];
        final int[] fifo=new int[width*height];
        int front=0,end=0;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            if(x>0&&x<width-1&&y>0&&y<height-1)continue;
            final int i=y*width+x;
            if(mask.get(x+x0,y+y0)==0&&!seen[i]) {
                seen[i]=true;fifo[end++]=i;
            }
        }
        while(front<end) {
            final int i=fifo[front++];
            final int x=i%width,y=i/width;
            for(int yy=Math.max(0,y-1);yy<=Math.min(height-1,y+1);yy++)
                for(int xx=Math.max(0,x-1);xx<=Math.min(width-1,x+1);xx++) {
                    final int j=yy*width+xx;
                    if(!seen[j]&&mask.get(x0+xx,y0+yy)==0){seen[j]=true;fifo[end++]=j;}
                }
        }
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            final int i=y*width+x;
            if(mask.get(x+x0,y+y0)==0&&!seen[i])return true;
        }
        return false;
    }

    private static final class Peak {
        final int x,y;final double r;
        Peak(int x,int y,double r){this.x=x;this.y=y;this.r=r;}
    }
    private static final class Region {
        final int width,height;
        final List<Integer> pixels=new ArrayList<>();
        int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,maxX=0,maxY=0;
        Region(int width,int height){this.width=width;this.height=height;}
    }
}
