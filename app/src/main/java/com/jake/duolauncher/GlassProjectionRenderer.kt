package com.jake.duolauncher

import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlin.math.*

/** Adapted from GlassProjection LiveBlurPyramid / LiveMirrorWindowProbe (MIT).
 * RenderNodes replace shell/OES mirror textures. No screenshots, readback, blocking
 * fences, SurfaceView or second window: native widgets and Haze stay in the same frame.
 * Seven progressively smaller GPU levels are packed with gutters into one shader input.
 * Source is decoded once; every convolution and variance interpolation is linear-light.
 */
internal const val GLASS_LINEAR_SHADER = """
uniform shader content;
half4 main(float2 p) {
    float3 c=float3(content.eval(p).rgb);
    return half4(mix(c/12.92,pow((c+0.055)/1.055,float3(2.4)),step(float3(0.04045),c)),1);
}
"""

internal const val GLASS_PAD_SHADER = """
uniform shader content;
uniform float2 size;
uniform float contentScale;
half4 main(float2 p) {
    float2 q=(p/size-(1.0-contentScale)*0.5)/contentScale;
    // Side extension avoids vertical black stripes; the top/bottom paper edge is black.
    if(q.y<0.0 || q.y>1.0) return half4(0,0,0,1);
    q=clamp(q,float2(0.5)/size,1.0-float2(0.5)/size);
    return half4(content.eval(q*size).rgb,1);
}
"""

internal const val GLASS_GAUSSIAN_SHADER = """
uniform shader content;
uniform float2 stepSize;
uniform float2 size;
half3 read(float2 p) { return content.eval(clamp(p,float2(0.5),size-0.5)).rgb; }
half4 main(float2 p) {
    half3 c=read(p)*0.227027027;
    c+=(read(p+stepSize*1.384615385)+read(p-stepSize*1.384615385))*0.316216216;
    c+=(read(p+stepSize*3.230769231)+read(p-stepSize*3.230769231))*0.070270270;
    return half4(c,1);
}
"""

internal const val GLASS_PROJECTION_SHADER = """
uniform shader content;
uniform float2 size;
uniform float2 baseSize;
uniform float4 tiles[7];
uniform float tilt;
uniform float crop;
uniform float inner;
uniform float wholeDesktop;
uniform float hingeBlend;
uniform float blurStrength;
uniform float4 corners;
const float CONTENT_SCALE=0.88;
float3 encode(float3 c) {
    c=max(c,float3(0));
    return mix(c*12.92,1.055*pow(c,float3(1.0/2.4))-0.055,step(float3(0.0031308),c));
}
float4 tile(float l) {
    if(l<0.5)return tiles[0]; if(l<1.5)return tiles[1]; if(l<2.5)return tiles[2];
    if(l<3.5)return tiles[3]; if(l<4.5)return tiles[4]; if(l<5.5)return tiles[5]; return tiles[6];
}
float3 level(float2 uv,float l) {
    float4 r=tile(l);
    return float3(content.eval(r.xy+clamp(uv*r.zw,float2(0.5),r.zw-0.5)).rgb);
}
float3 at(float2 q,float sigma) {
    float2 p=(1.0-CONTENT_SCALE)*0.5+q*CONTENT_SCALE;
    float variance=sigma*sigma;
    float l=clamp(0.5*log2(1.0+3.0*variance/2.854),0.0,6.0);
    float lo=floor(l); float hi=min(lo+1.0,6.0);
    float a=2.854*(pow(4.0,lo)-1.0)/3.0;
    float b=2.854*(pow(4.0,hi)-1.0)/3.0;
    float f=clamp((variance-a)/max(b-a,0.0001),0.0,1.0);
    return encode(mix(level(p,lo),level(p,hi),f));
}
half4 main(float2 pixel) {
    // A source with shadows/Haze outsets must never overwrite the neighbouring leaf.
    if(pixel.x<0.0||pixel.y<0.0||pixel.x>=size.x||pixel.y>=size.y)return half4(0);
    float2 u=pixel/size;
    float leafFraction=wholeDesktop>0.5?0.5:1.0;
    float hinge=inner>0.5?leafFraction:0.0;
    if(wholeDesktop>0.5 && u.x>=hinge)return half4(0);
    float coverage=wholeDesktop>0.5?smoothstep(0.0,max(hingeBlend,1.0),size.x*hinge-pixel.x):1.0;
    float x=(u.x-hinge)/leafFraction*0.073;
    float y=(u.y-0.5)*0.16;
    float3 eye=float3(inner>0.5?0.0:0.0365,0,0.6);
    float3 P=float3(x*cos(tilt),y,abs(x)*sin(tilt));
    float3 D=normalize(P-eye);
    float t=-P.z/min(D.z,-0.0001);
    float3 Q=P+t*D;
    float2 paperQ=float2(Q.x/0.073*leafFraction+hinge,Q.y/0.16+0.5);
    float a=clamp(abs(x)/0.073,0.0,1.0);
    float2 q=float2(hinge+x*(1.0-crop*a)/0.073*leafFraction,u.y);
    // Inner spill is intentionally zero: the other MiDuo leaf is a separate live pane.
    float d=inner>0.5?abs(x):0.073*0.18+abs(x)*(1.0-0.18);
    float3 B=float3((inner>0.5?-d:d)*cos(tilt),y,d*sin(tilt));
    float gap=length(B-eye)*B.z/(0.6-B.z);
    // Upstream inner sigma is measured on a two-leaf canvas; ours is one leaf.
    float s=min(0.03,max(gap,0.0)*(inner>0.5?0.55:0.75));
    float2 pixelScale=baseSize*CONTENT_SCALE;
    float sigma=s*pixelScale.x*(inner>0.5 && wholeDesktop<0.5?2.0:1.0)*blurStrength;
    float leafPixels=pixelScale.x*leafFraction;
    float edgeY=min(paperQ.y,1.0-paperQ.y)*pixelScale.y;
    // Replace the demo's artificial equal corners with the current physical radii.
    float radius=u.x<leafFraction*0.5?(u.y<0.5?corners.x:corners.z):(u.y<0.5?corners.y:corners.w);
    radius=min(radius*pixelScale.x/size.x,min(pixelScale.x,pixelScale.y)*0.5);
    float sideDistance=min(u.x,leafFraction-u.x)*pixelScale.x;
    float cornerX=max(radius-sideDistance,0.0);
    float cornerInset=radius-sqrt(max(radius*radius-cornerX*cornerX,0.0));
    float boundary=cornerInset-edgeY;
    float inward=max(-boundary,0.0)/max(leafPixels*0.18,1.0);
    float influence=exp(-0.5*inward*inward);
    float edgeSigma=leafPixels*0.025*sin(tilt)*blurStrength;
    sigma*=1.4;
    float baseVariance=sigma*sigma; float edgeVariance=edgeSigma*edgeSigma;
    float paperSigma=min(62.0,sqrt(baseVariance+edgeVariance));
    sigma=min(62.0,sqrt(baseVariance+edgeVariance*influence*influence));
    float feather=max(paperSigma*3.2,0.5);
    float paper=smoothstep(-feather,feather,-boundary);
    // Opaque optical image: no crossfade of shifted copies, no transparent glass holes.
    return half4(at(q,sigma)*paper*coverage,coverage);
}
"""

@RequiresApi(33)
internal class GlassProjectionRenderer : AutoCloseable {
    private val linearSource=RenderNode("MiDuo-full-resolution-linear-source")
    private val levels=Array(7) { RenderNode("MiDuo-linear-level-$it") }
    private val scratch=Array(6) { RenderNode("MiDuo-gaussian-x-$it") }
    private val output=RenderNode("MiDuo-glass-projection")
    private val linear=RuntimeShader(GLASS_LINEAR_SHADER)
    private val padded=RuntimeShader(GLASS_PAD_SHADER)
    private val horizontal=Array(6) { RuntimeShader(GLASS_GAUSSIAN_SHADER) }
    private val vertical=Array(6) { RuntimeShader(GLASS_GAUSSIAN_SHADER) }
    private val projection=RuntimeShader(GLASS_PROJECTION_SHADER)
    private val scope=CanvasDrawScope()
    private var width=0; private var height=0
    private var baseWidth=0; private var baseHeight=0
    private val widths=IntArray(7); private val heights=IntArray(7)
    private val tiles=FloatArray(28)
    private var released=false

    private fun configure(w: Int,h: Int) {
        if(width==w && height==h && !released)return
        width=w; height=h; released=false
        linearSource.setPosition(0,0,w,h); linearSource.setClipToBounds(true)
        linearSource.setRenderEffect(RenderEffect.createRuntimeShaderEffect(linear,"content"))
        // Bound GPU work on the real inner panel. Pyramid total area is < 1.34 base images.
        val scale=min(1f,768f/max(w,h))
        baseWidth=max(64,(w*scale).roundToInt()); baseHeight=max(64,(h*scale).roundToInt())
        var rightY=0
        for(i in 0..6) {
            widths[i]=max(1,(baseWidth+(1 shl i)-1) shr i)
            heights[i]=max(1,(baseHeight+(1 shl i)-1) shr i)
            val left=if(i==0) 0 else baseWidth+2
            val top=if(i==0) 0 else rightY
            tiles[i*4]=left.toFloat(); tiles[i*4+1]=top.toFloat()
            tiles[i*4+2]=widths[i].toFloat(); tiles[i*4+3]=heights[i].toFloat()
            if(i>0)rightY+=heights[i]+2
            levels[i].setPosition(0,0,widths[i],heights[i]); levels[i].setClipToBounds(true)
            if(i>0) {
                scratch[i-1].setPosition(0,0,widths[i],heights[i]); scratch[i-1].setClipToBounds(true)
                horizontal[i-1].setFloatUniform("stepSize",1f,0f)
                vertical[i-1].setFloatUniform("stepSize",0f,1f)
                for(shader in listOf(horizontal[i-1],vertical[i-1]))shader.setFloatUniform("size",widths[i].toFloat(),heights[i].toFloat())
                scratch[i-1].setRenderEffect(RenderEffect.createRuntimeShaderEffect(horizontal[i-1],"content"))
                levels[i].setRenderEffect(RenderEffect.createRuntimeShaderEffect(vertical[i-1],"content"))
                scratch[i-1].beginRecording().apply {
                    scale(widths[i].toFloat()/widths[i-1],heights[i].toFloat()/heights[i-1])
                    drawRenderNode(levels[i-1])
                }; scratch[i-1].endRecording()
                levels[i].beginRecording().drawRenderNode(scratch[i-1]); levels[i].endRecording()
            }
        }
        padded.setFloatUniform("size",baseWidth.toFloat(),baseHeight.toFloat())
        padded.setFloatUniform("contentScale",GlassProjectionMath.CONTENT_SCALE)
        levels[0].setRenderEffect(RenderEffect.createRuntimeShaderEffect(padded,"content"))
        levels[0].beginRecording().apply {
            scale(baseWidth.toFloat()/w,baseHeight.toFloat()/h)
            drawRenderNode(linearSource)
        }; levels[0].endRecording()
        projection.setFloatUniform("size",w.toFloat(),h.toFloat())
        projection.setFloatUniform("baseSize",baseWidth.toFloat(),baseHeight.toFloat())
        projection.setFloatUniform("tiles",tiles)
        output.setPosition(0,0,max(w,baseWidth+2+widths[1]),max(h,max(baseHeight,rightY)))
        output.setClipToBounds(true)
        val canvas=output.beginRecording()
        for(i in 0..6) {
            canvas.withTranslation(tiles[i*4],tiles[i*4+1]) { drawRenderNode(levels[i]) }
        }
        output.endRecording()
    }

    fun draw(drawScope: androidx.compose.ui.graphics.drawscope.DrawScope, source: GraphicsLayer, amount: Float,
             inner: Boolean, blur: Boolean, corners: FloatArray, wholeDesktop: Boolean = false) {
        configure(drawScope.size.width.roundToInt(),drawScope.size.height.roundToInt())
        // Decode BEFORE the first downsample. Reversing these operations destroys
        // linear-light energy on small white text/checkers (sRGB .5 instead of .735).
        val recording=linearSource.beginRecording()
        scope.draw(drawScope,drawScope.layoutDirection,Canvas(recording),drawScope.size) { drawLayer(source) }
        linearSource.endRecording()
        val tilt=GlassProjectionMath.opticalTilt(amount,inner)
        projection.setFloatUniform("tilt",Math.toRadians(tilt.toDouble()).toFloat())
        projection.setFloatUniform("crop",tilt*.004f)
        projection.setFloatUniform("inner",if(inner)1f else 0f)
        projection.setFloatUniform("wholeDesktop",if(wholeDesktop)1f else 0f)
        projection.setFloatUniform("hingeBlend",min(drawScope.size.width*.035f,24f*drawScope.density))
        projection.setFloatUniform("blurStrength",if(blur)GlassProjectionMath.BLUR_STRENGTH else 0f)
        projection.setFloatUniform("corners",corners[0],if(inner)0f else corners[1],corners[2],if(inner)0f else corners[3])
        // RenderNode cannot observe mutable RuntimeShader uniforms without a fresh effect.
        output.setRenderEffect(RenderEffect.createRuntimeShaderEffect(projection,"content"))
        val target=drawScope.drawContext.canvas.nativeCanvas
        target.withClip(0f,0f,drawScope.size.width,drawScope.size.height) { drawRenderNode(output) }
    }

    override fun close() {
        if(released)return
        output.discardDisplayList(); linearSource.discardDisplayList()
        levels.forEach { it.discardDisplayList() }; scratch.forEach { it.discardDisplayList() }
        released=true
    }
}

@RequiresApi(33)
@Composable
internal fun Modifier.glassProjection(amount: () -> Float, inner: Boolean, blur: Boolean, corners: FloatArray,
    wholeDesktop: Boolean = false): Modifier {
    val source=rememberGraphicsLayer()
    val renderer=remember { GlassProjectionRenderer() }
    DisposableEffect(renderer) { onDispose { renderer.close() } }
    return drawWithContent {
        val raw=amount(); val value=if(raw.isFinite())raw.coerceIn(0f,1f) else 0f
        if(value<=.001f || size.width<1f || size.height<1f || !drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
            // Exact native endpoint, with no blur buffers retained by dormant panes.
            renderer.close(); drawContent()
        } else {
            source.record { this@drawWithContent.drawContent() }
            // Native pixels are the base, including the dock/right page. Only the
            // physical left leaf overlays it; a short premultiplied fade closes at x=W/2.
            if(wholeDesktop)drawLayer(source)
            renderer.draw(this,source,value,inner,blur,corners,wholeDesktop)
        }
    }
}
