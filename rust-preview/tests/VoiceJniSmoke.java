package lp2ln_android.krusalov.org.network;

import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

// Run two independent JVMs against the same JNI exports as the device preview.
// No Android audio device is simulated by this test.
class Lp2lnNativeBridge {
    static native String nativeStart(String directory, String bootstrap);
    static native String nativeStop();
}
class Lp2lnRealtimeBridge {
    static native String nativeStatus();
    static native String nativeOpen(String peer, String addr, int protocol);
    static native String nativeAccept(int timeout);
    static native String nativeAuthorize(String peer, int protocol);
    static native String nativeClose(long handle);
}
class VoiceBridge {
    static native String begin(long handle, boolean caller);
    static native String step(long handle);
    static native String decide(long handle, boolean consent);
    static native String startMedia(long handle);
    static native int capture(long handle, short[] pcm, int ageMs);
    static native int ingress(long handle);
    static native int play(long handle, short[] pcm);
    static native String rotate(long handle);
    static native String stats(long handle);
}

public class VoiceJniSmoke {
    static String field(String text,String name) {
        // Only the bridge's flat bool/number/hex/type fields are consumed.
        Matcher m=Pattern.compile("\""+name+"\":(?:\"([^\"]*)\"|([0-9a-z]+))").matcher(text);
        if(!m.find())throw new IllegalArgumentException("Missing "+name);
        return m.group(1)!=null?m.group(1):m.group(2);
    }
    static String ok(String value) {
        if(!field(value,"ok").equals("true"))throw new IllegalStateException(value);
        return value;
    }
    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        Path dir=Path.of(args[1]);
        boolean caller=args[2].equals("caller");
        Path local=dir.resolve(args[2]);
        Files.createDirectories(local);
        Lp2lnNativeBridge.nativeStart(local.toString(),"");
        try {
            String status=null;
            for(int i=0;i<100;i++){
                status=Lp2lnRealtimeBridge.nativeStatus();
                if(field(status,"ok").equals("true"))break;
                Thread.sleep(30);
            }
            Path temp=local.resolve("tmp");
            Files.writeString(temp,ok(status));
            Files.move(temp,local.resolve("ready"),StandardCopyOption.ATOMIC_MOVE);
            Path other=dir.resolve(caller?"listener":"caller");
            while(!Files.exists(other.resolve("ready")))Thread.sleep(20);
            String peerStatus=Files.readString(other.resolve("ready"));
            String peer=field(peerStatus,"peerId");
            String open;
            if(caller){
                while(!Files.exists(other.resolve("allowed")))Thread.sleep(20);
                open=ok(Lp2lnRealtimeBridge.nativeOpen(peer,"127.0.0.1:"+field(peerStatus,"port"),0xff82));
            } else {
                ok(Lp2lnRealtimeBridge.nativeAuthorize(peer,0xff82));
                Files.createFile(local.resolve("allowed"));
                open=ok(Lp2lnRealtimeBridge.nativeAccept(15_000));
            }
            if(!peer.equals(field(open,"peerId")))throw new AssertionError("wrong peer");
            long h=Long.parseLong(field(open,"handle"));
            try { runCall(h,caller); } finally { ok(Lp2lnRealtimeBridge.nativeClose(h)); }
        } finally { Lp2lnNativeBridge.nativeStop(); }
    }
    static void runCall(long h,boolean caller) throws Exception {
        ok(VoiceBridge.begin(h,caller));
        if(field(VoiceBridge.startMedia(h),"ok").equals("true"))throw new AssertionError("early mic");
        AtomicBoolean stop=new AtomicBoolean();
        AtomicInteger audible=new AtomicInteger();
        ExecutorService workers=Executors.newFixedThreadPool(3);
        java.util.List<Future<?>> jobs=new java.util.ArrayList<>();
        long active=0;
        boolean rotated=false,finished=false;
        try {
            for(int i=0;i<200;i++){
                String event=ok(VoiceBridge.step(h));
                if(field(event,"finished").equals("true")){finished=true;break;}
                if(field(event,"incoming").equals("true"))ok(VoiceBridge.decide(h,true));
                if(field(event,"state").equals("Active") && active==0){
                    ok(VoiceBridge.startMedia(h));active=System.nanoTime();
                    jobs.add(workers.submit(()->{
                        while(!stop.get())VoiceBridge.ingress(h);
                    }));
                    jobs.add(workers.submit(()->{
                        short[] pcm=new short[960];
                        double hz=caller?440:660;
                        for(int j=0;j<pcm.length;j++)pcm[j]=(short)(9000*Math.sin(j*hz*2*Math.PI/48000));
                        while(!stop.get()){
                            if(VoiceBridge.capture(h,pcm,20)<0)throw new AssertionError("capture failed");
                            Thread.sleep(20);
                        }
                        return null;
                    }));
                    jobs.add(workers.submit(()->{
                        short[] pcm=new short[960];
                        while(!stop.get()){
                            if(VoiceBridge.play(h,pcm)!=0)throw new AssertionError("play failed");
                            for(short s:pcm)if(Math.abs((int)s)>1200){audible.incrementAndGet();break;}
                            Thread.sleep(20);
                        }
                        return null;
                    }));
                }
                if(active!=0 && caller){
                    long age=System.nanoTime()-active;
                    if(!rotated && age>1_000_000_000L){ok(VoiceBridge.rotate(h));rotated=true;}
                    if(age>3_000_000_000L)ok(VoiceBridge.decide(h,false));
                }
                Thread.sleep(70);
            }
            stop.set(true);
            for(Future<?> job:jobs)job.get(1,TimeUnit.SECONDS);
            System.out.println("caller="+caller+" audibleFrames="+audible+" "+ok(VoiceBridge.stats(h)));
            if(!finished || audible.get()<100)throw new AssertionError("incomplete call");
            Thread.sleep(caller?250:400);
        } finally {stop.set(true);workers.shutdownNow();}
    }
}
