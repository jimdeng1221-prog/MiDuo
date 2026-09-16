package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class GlassProjectionMathTest {
    @Test fun `physical tilt uses upstream degrees and smooth onset on both panels`() {
        for(inner in listOf(false,true)) {
            assertEquals(0f,GlassProjectionMath.opticalTilt(0f,inner),0f)
            assertEquals(85f,GlassProjectionMath.opticalTilt(1f,inner),0f)
            assertEquals(0f,GlassProjectionMath.opticalTilt(Float.NaN,inner),0f)
            val values=(0..100).map { GlassProjectionMath.opticalTilt(it/100f,inner) }
            assertTrue(values.zipWithNext().all { (a,b)->b>=a })
            assertTrue(GlassProjectionMath.opticalTilt(.02f,inner)>0f)
        }
        assertEquals(85f,GlassProjectionMath.opticalTilt(.5f,true),.001f)
        assertEquals(60.16f,GlassProjectionMath.opticalTilt(.5f,false),.001f)
    }
    @Test fun `angle follower is frame rate independent bounded and reverses immediately`() {
        for(inner in listOf(false,true)) {
            val sixty=(1..6).fold(40f) { current,_->GlassProjectionMath.followAngle(current,60f,16,inner,0f) }
            val oneTwenty=(1..12).fold(40f) { current,_->GlassProjectionMath.followAngle(current,60f,8,inner,0f) }
            assertEquals(sixty,oneTwenty,.0001f)
            val follower=GlassAngleFollow(); follower.reset(0,40f)
            val opening=follower.update(16,40f,60f,inner)
            assertTrue(opening in 40f..60f)
            val closing=follower.update(32,opening,20f,inner)
            assertTrue(closing>=20f && closing<opening)
            assertEquals(closing,follower.update(48,closing,Float.NaN,inner),0f)
        }
    }
    @Test fun `ray plane projection agrees with upstream camera model on both leaves`() {
        for (inner in listOf(false,true)) for (degrees in listOf(0f,1f,5f,30f,60f,85f)) {
            val x=if(inner)-.061 else .061; val y=.071
            val theta=Math.toRadians(degrees.toDouble()); val px=x*cos(theta); val z=abs(x)*sin(theta)
            val eye=if(inner)0.0 else GlassProjectionMath.LEAF_WIDTH*.5
            val k=GlassProjectionMath.EYE_Z/(GlassProjectionMath.EYE_Z-z)
            val expectedX=eye+(px-eye)*k; val expectedY=y*k
            val actual=GlassProjectionMath.paperPoint(x,y,degrees/85f,inner)
            assertEquals(expectedX,actual.x,1e-7); assertEquals(expectedY,actual.y,1e-7)
            assertEquals(sqrt((expectedX-px).pow(2)+(expectedY-y).pow(2)+z*z),actual.distance,1e-7)
        }
    }
    @Test fun `zero angle and hinge are exact optical endpoints`() {
        for(inner in listOf(false,true)) {
            val zero=GlassProjectionMath.paperPoint(-.03,.06,0f,inner)
            assertEquals(-.03,zero.x,1e-7); assertEquals(.06,zero.y,1e-7); assertEquals(0.0,zero.distance,1e-7)
            for(step in 0..100) {
                val hinge=GlassProjectionMath.paperPoint(0.0,.06,step/100f,inner)
                assertEquals(0.0,hinge.x,1e-7); assertEquals(.06,hinge.y,1e-7); assertEquals(0.0,hinge.distance,1e-7)
            }
        }
    }
    @Test fun `small angles respond continuously and reverse without changing path`() {
        val forward=(0..100).map { GlassProjectionMath.crop(it/100f) }
        assertEquals(0f,forward.first(),0f); assertEquals(.34f,forward.last(),1e-6f)
        assertTrue(forward.zipWithNext().all { (a,b)->b>a })
        assertEquals(forward.reversed(),(100 downTo 0).map { GlassProjectionMath.crop(it/100f) })
        assertEquals(0f,GlassProjectionMath.tiltDegrees(Float.NaN),0f)
    }
    @Test fun `pyramid blends variance rather than radius or display gamma`() {
        for(level in 0..5) {
            val lo=GlassProjectionMath.levelVariance(level); val hi=GlassProjectionMath.levelVariance(level+1)
            val midpoint=GlassProjectionMath.levelMix(sqrt((lo+hi)/2))
            assertEquals(level,midpoint.low); assertEquals(level+1,midpoint.high)
            assertEquals(.5f,midpoint.weight,1e-5f)
        }
        assertEquals(0,GlassProjectionMath.levelMix(0f).low)
        assertEquals(6,GlassProjectionMath.levelMix(10000f).high)
        assertEquals(0,GlassProjectionMath.levelMix(Float.NaN).low)
    }
    @Test fun `physical asymmetric corners retain individual radii`() {
        assertEquals(154f,GlassProjectionMath.cornerInset(154f,0f),1e-4f)
        assertEquals(28f,GlassProjectionMath.cornerInset(28f,0f),1e-4f)
        assertEquals(0f,GlassProjectionMath.cornerInset(154f,154f),1e-4f)
        assertEquals(0f,GlassProjectionMath.cornerInset(0f,0f),1e-4f)
        assertTrue(GlassProjectionMath.cornerInset(154f,10f)>GlassProjectionMath.cornerInset(28f,10f))
    }
}
