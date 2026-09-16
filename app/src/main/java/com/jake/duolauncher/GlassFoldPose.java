// Adapted from GlassProjection v0.4.30 (18489f32), MIT.
// Copyright (c) 2026 spideytznn, 6ZLeo and contributors.
// Full license: assets/licenses/GlassProjection-MIT.txt.
package com.jake.duolauncher;

/** Immutable snapshot: validated endpoint signals take priority over noisy hinge estimates. */
final class GlassFoldPose {
    // -2: sensor unavailable; -1: waiting for its initial event; 0: open; 1: closed.
    final int foldStatus;
    // lhasa fold_status values[2]: observed 0 at contact, 1 after separation.
    // -2: unvalidated device; -1: no valid contact event yet (use coarse state).
    final int contactStatus;
    // lhasa fold_status values[4]: 3 is OPENED, 2 is HALF_OPENED; 0/1 are
    // folded postures, not proof of contact. Validated against real flat tilt
    // and folding on this device; never decode this field on other devices.
    final int postureStatus;
    final float rawAngle;
    // lhasa's continuous digital Hall sensor: -2 unavailable, -1 pending,
    // 0 contact, 1 separated. Unlike fold_status, this is refreshed at 50 Hz.
    final int directContactStatus;
    private final long directContactAt;
    // Validated devices must not fall back to hinge-only input if both physical sources fail.
    private final boolean physicalRequired;
    private final boolean flatLatched;
    // Sensor timestamps share Android's elapsed-realtime nanosecond clock.
    // Zero means no observation/latch. Keep stream ordering separate: a queued
    // fold event must not undo a newer hinge endpoint, or vice versa.
    private final long angleAt,foldAt,closedAt;
    GlassFoldPose(boolean physicalSensor){this(physicalSensor,false);}
    GlassFoldPose(boolean physicalSensor,boolean contactSupported){this(physicalSensor,contactSupported,false);}
    GlassFoldPose(boolean physicalSensor,boolean contactSupported,boolean directContactSupported){this(Float.NaN,physicalSensor?-1:-2,physicalSensor&&contactSupported?-1:-2,physicalSensor&&contactSupported?-1:-2,false,0,0,0,contactSupported&&directContactSupported?-1:-2,0,contactSupported);}
    private GlassFoldPose(float rawAngle,int foldStatus,int contactStatus,int postureStatus,boolean wasFlat,long angleAt,long foldAt,long closedAt,int directContactStatus,long directContactAt,boolean physicalRequired){
        this.rawAngle=rawAngle;this.foldStatus=foldStatus;this.contactStatus=contactStatus;this.postureStatus=postureStatus;
        this.physicalRequired=physicalRequired;
        this.angleAt=angleAt;this.foldAt=foldAt;this.closedAt=closedAt;
        this.directContactStatus=directContactStatus;this.directContactAt=directContactAt;
        // OPENED can arrive before the hinge has reached the clear endpoint.
        // Follow the hinge until BOTH signals agree, then ignore angle-only tilt.
        flatLatched=!closed()&&postureStatus==3&&(wasFlat||Float.isFinite(rawAngle)&&rawAngle>=175);
    }
    private long nextAt(){return Math.max(Math.max(angleAt,foldAt),directContactAt)+1;}
    GlassFoldPose withAngle(float angle){return withAngle(angle,nextAt());}
    GlassFoldPose withAngle(float angle,long at){
        if(!Float.isFinite(angle)||angle<0||angle>180||at<=angleAt)return this;
        long closure=closedAt;
        // The vendor contact field can stay "separated" for minutes after
        // closure. Reaching the clear endpoint in the folded posture closes
        // that gap, without treating the entire coarse CLOSED range as shut.
        if(directContactStatus==-2&&contactStatus>=0&&foldStatus==1&&postureStatus==0&&angle<=1&&at>=foldAt)closure=at;
        return new GlassFoldPose(angle,foldStatus,contactStatus,postureStatus,flatLatched,at,foldAt,closure,directContactStatus,directContactAt,physicalRequired);
    }
    GlassFoldPose withFoldStatus(float status){
        return withFoldEvent(new float[]{status});
    }
    GlassFoldPose withFoldEvent(float[] values){return withFoldEvent(values,nextAt());}
    GlassFoldPose withFoldEvent(float[] values,long at){
        if(foldStatus==-2||values==null||values.length==0||at<=foldAt)return this;
        boolean validFold=values[0]==0||values[0]==1;
        int fold=validFold?(int)values[0]:foldStatus;
        int contact=contactStatus,state=postureStatus;
        long closure=closedAt;
        if(contactStatus!=-2&&values.length>=11){
            boolean validContact=values[2]==0||values[2]==1;
            boolean validPosture=values[4]==0||values[4]==1||values[4]==2||values[4]==3;
            if(validContact)contact=(int)values[2];
            if(validPosture)state=(int)values[4];
            if(directContactStatus==-2){
            if(validContact&&contact==0)closure=Math.max(closure,at);
            // A cached contact=1 or angle-only movement cannot unlock. Require
            // a fresh complete separation event measured after the endpoint.
            // Its angle must also be outside the <=1 degree clear endpoint.
            else if(validContact&&contact==1&&validFold&&validPosture&&at>closure
                    &&Float.isFinite(values[1])&&values[1]>1&&values[1]<=180)closure=0;
            // Hinge-first delivery of closure: use the newer endpoint, not the
            // older contact snapshot. This also handles service startup.
            if(contact>=0&&fold==1&&state==0&&Float.isFinite(rawAngle)
                    &&rawAngle<=1&&angleAt>=at)closure=Math.max(closure,angleAt);
            }
        }
        return new GlassFoldPose(rawAngle,fold,contact,state,flatLatched,angleAt,at,closure,directContactStatus,directContactAt,physicalRequired);
    }
    GlassFoldPose withDirectContactEvent(float[] values,long at){
        if(directContactStatus==-2||values==null||values.length<9||at<=directContactAt)return this;
        if(values[0]!=0&&values[0]!=1)return this;
        // The digital switch uses a wide 681/1630 hysteresis: the user-confirmed
        // small gap still reports CLOSED at |field|~1200. Use the measured
        // contact threshold with a narrower 10% release margin instead. Captured
        // full-contact tilt remains above this threshold. Profile: lhasa only.
        float field=Math.abs(values[4]),contactThreshold=values[6];
        if(!Float.isFinite(field)||!Float.isFinite(contactThreshold)||contactThreshold<=0
                ||!Float.isFinite(values[5])||values[5]<0||values[5]>=contactThreshold)return this;
        int contact=directContactStatus>=0?directContactStatus:(int)values[0];
        if(field>=contactThreshold)contact=0;
        else if(field<=contactThreshold*.9f)contact=1;
        return new GlassFoldPose(rawAngle,foldStatus,contactStatus,postureStatus,flatLatched,
            angleAt,foldAt,0,contact,at,physicalRequired);
    }
    GlassFoldPose withDirectContactAvailable(boolean available){
        if(available==(directContactStatus!=-2))return this;
        return new GlassFoldPose(rawAngle,foldStatus,contactStatus,postureStatus,flatLatched,
            angleAt,foldAt,directContactStatus==0?directContactAt:closedAt,available?-1:-2,0,physicalRequired);
    }
    long directContactAgeMs(long now){return directContactAt==0?Long.MAX_VALUE:Math.max(0,(now-directContactAt)/1000000);}
    GlassFoldPose expireDirectContact(long now){
        if(directContactStatus<0||directContactAgeMs(now)<=500)return this;
        // Never keep rendering from a stale separated sample if the host dies.
        // A fresh shell sample restores the measured state automatically.
        return new GlassFoldPose(rawAngle,foldStatus,contactStatus,postureStatus,flatLatched,
            angleAt,foldAt,0,-1,directContactAt,physicalRequired);
    }
    boolean closedLatched(){return directContactStatus==-2?closedAt>0:directContactStatus==0;}
    private boolean closed(){
        if(directContactStatus!=-2)return directContactStatus!=1;
        return physicalRequired&&foldStatus==-2||closedLatched()||(contactStatus>=0?contactStatus==0:foldStatus==-1||foldStatus==1);
    }
    // Confirmed flat protection, not merely the raw vendor OPENED report.
    boolean fullyOpened(){return flatLatched;}
    boolean blocksProjection(){return closed()||fullyOpened();}
    // Preserve which endpoint was reached: 0 would make the screen controller
    // treat a flat phone as closed and release/choose the wrong panel.
    float angle(){return closed()?0:fullyOpened()?180:rawAngle;}
}
