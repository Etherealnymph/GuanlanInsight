package com.etherealnymph.guanlan;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * A small orbital "lens" mark used while a page is being fetched.
 *
 * <p>Draws two static orbit rings, a rotating comet arc and a central eye/wave
 * glyph. It intentionally uses only framework drawing APIs so the app has no
 * animation dependencies.</p>
 */
public class OrbitLoadingView extends View {

    private static final int ACCENT = Color.rgb(100, 181, 255);
    private static final int VIOLET = Color.rgb(183, 156, 255);
    private static final int TEXT = Color.rgb(234, 246, 255);

    private final Paint orbitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint eyePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pupilPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF arcBounds = new RectF();
    private final Path eyePath = new Path();

    private float centerX;
    private float centerY;
    private float orbitRadius;
    private float arcRadius;
    private float rotation;
    private float density;

    private ValueAnimator animator;

    public OrbitLoadingView(Context context) {
        this(context, null);
    }

    public OrbitLoadingView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public OrbitLoadingView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = getResources().getDisplayMetrics().density;

        orbitPaint.setStyle(Paint.Style.STROKE);
        orbitPaint.setStrokeWidth(dp(1f));
        orbitPaint.setColor(Color.argb(38, 255, 255, 255));

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(dp(2.4f));
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(TEXT);
        dotPaint.setShadowLayer(dp(5f), 0f, 0f, Color.argb(160, 100, 181, 255));

        eyePaint.setStyle(Paint.Style.STROKE);
        eyePaint.setStrokeWidth(dp(1.7f));
        eyePaint.setStrokeCap(Paint.Cap.ROUND);
        eyePaint.setStrokeJoin(Paint.Join.ROUND);

        pupilPaint.setStyle(Paint.Style.FILL);
        pupilPaint.setColor(TEXT);

        starPaint.setStyle(Paint.Style.FILL);
        starPaint.setColor(Color.argb(150, 183, 156, 255));
    }

    private float dp(float value) {
        return value * density;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centerX = w / 2f;
        centerY = h / 2f;
        orbitRadius = Math.min(w, h) * 0.405f;
        arcRadius = orbitRadius;

        arcBounds.set(
                centerX - arcRadius,
                centerY - arcRadius,
                centerX + arcRadius,
                centerY + arcRadius);

        eyePath.reset();
        float eyeWidth = orbitRadius * 0.84f;
        float eyeHeight = eyeWidth * 0.44f;
        eyePath.moveTo(centerX - eyeWidth / 2f, centerY);
        eyePath.cubicTo(
                centerX - eyeWidth * 0.23f, centerY - eyeHeight,
                centerX + eyeWidth * 0.23f, centerY - eyeHeight,
                centerX + eyeWidth / 2f, centerY);
        eyePath.cubicTo(
                centerX + eyeWidth * 0.23f, centerY + eyeHeight,
                centerX - eyeWidth * 0.23f, centerY + eyeHeight,
                centerX - eyeWidth / 2f, centerY);
        eyePath.close();

        int[] colors = new int[]{
                Color.TRANSPARENT,
                ACCENT,
                VIOLET,
                Color.TRANSPARENT,
        };
        float[] stops = new float[]{0f, 0.28f, 0.72f, 1f};
        arcPaint.setShader(new SweepGradient(centerX, centerY, colors, stops));
        eyePaint.setShader(new LinearGradient(
                centerX - eyeWidth / 2f,
                centerY,
                centerX + eyeWidth / 2f,
                centerY,
                Color.rgb(211, 229, 255),
                VIOLET,
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float pulse = 0.92f + 0.08f * (float) Math.sin(Math.toRadians(rotation * 1.5f));

        canvas.save();
        canvas.rotate(rotation, centerX, centerY);
        canvas.drawArc(arcBounds, -24f, 260f, false, arcPaint);
        canvas.restore();

        double endAngle = Math.toRadians(rotation - 24f + 260f);
        float dotX = centerX + (float) Math.cos(endAngle) * arcRadius;
        float dotY = centerY + (float) Math.sin(endAngle) * arcRadius;
        canvas.drawCircle(dotX, dotY, dp(2.3f) * pulse, dotPaint);

        canvas.drawCircle(centerX, centerY, orbitRadius * 1.16f, orbitPaint);

        orbitPaint.setColor(Color.argb(22, 100, 181, 255));
        canvas.drawCircle(centerX, centerY, orbitRadius * 0.7f, orbitPaint);
        orbitPaint.setColor(Color.argb(38, 255, 255, 255));

        canvas.drawPath(eyePath, eyePaint);
        canvas.drawCircle(centerX, centerY, dp(3.2f) * pulse, pupilPaint);

        canvas.drawCircle(
                centerX + orbitRadius * 0.78f,
                centerY - orbitRadius * 0.88f,
                dp(1.2f),
                starPaint);
        canvas.drawCircle(
                centerX - orbitRadius * 0.95f,
                centerY - orbitRadius * 0.45f,
                dp(0.9f),
                starPaint);
    }

    /** Starts the idle rotation. Safe to call repeatedly. */
    public void start() {
        if (animator != null && animator.isRunning()) {
            return;
        }
        animator = ValueAnimator.ofFloat(0f, 360f);
        animator.setDuration(2400L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            rotation = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    /** Stops the animation and frees the animator. */
    public void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }
}
