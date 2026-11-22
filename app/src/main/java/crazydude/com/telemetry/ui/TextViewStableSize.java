package crazydude.com.telemetry.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.TypedValue;

import androidx.appcompat.widget.AppCompatTextView;

import crazydude.com.telemetry.R;

public class TextViewStableSize extends AppCompatTextView {

    // Sensor readouts are drawn smaller than the sizes written in the layouts, text and icon
    // alike, so that more of them fit into one row
    private static final float SCALE = 0.64f;

    //should fit this text
    private String mMinText = "";
    //width of icon and it's padding, if there is DrawableLeft icon.
    //It is to complex to calculate it, so we just provide explicit value here.
    private int iconWidthDp = 0;

    private int seenMaxWidth = 0;

    public TextViewStableSize(Context context) {
        this(context, null);
    }

    public TextViewStableSize(Context context, AttributeSet attrs) {
        super(context, attrs);
        setAttributes(attrs);
        setTextSize(TypedValue.COMPLEX_UNIT_PX, getTextSize() * SCALE);
        // Icons given in the layout were set up by the base class at their full size
        Drawable[] relative = getCompoundDrawablesRelative();
        if (relative[0] != null || relative[2] != null) {
            setCompoundDrawablesRelative(scaled(relative[0]), scaled(relative[1]), scaled(relative[2]), scaled(relative[3]));
        } else {
            Drawable[] absolute = getCompoundDrawables();
            setCompoundDrawables(scaled(absolute[0]), scaled(absolute[1]), scaled(absolute[2]), scaled(absolute[3]));
        }
    }

    private static Drawable scaled(Drawable drawable) {
        if (drawable != null) {
            drawable.setBounds(0, 0,
                    Math.round(drawable.getIntrinsicWidth() * SCALE),
                    Math.round(drawable.getIntrinsicHeight() * SCALE));
        }
        return drawable;
    }

    @Override
    public void setCompoundDrawablesWithIntrinsicBounds(Drawable left, Drawable top, Drawable right, Drawable bottom) {
        setCompoundDrawables(scaled(left), scaled(top), scaled(right), scaled(bottom));
    }

    @Override
    public void setCompoundDrawablesRelativeWithIntrinsicBounds(Drawable start, Drawable top, Drawable end, Drawable bottom) {
        setCompoundDrawablesRelative(scaled(start), scaled(top), scaled(end), scaled(bottom));
    }

    private void setAttributes(AttributeSet attrs) {
        mMinText = "";
        iconWidthDp = 0;

        if (attrs != null) {
            TypedArray a = getContext().obtainStyledAttributes(attrs, R.styleable.TextViewStableSize);
            if (a.hasValue(R.styleable.TextViewStableSize_minText)) {
                mMinText = a.getString(R.styleable.TextViewStableSize_minText);
            }
            if (a.hasValue(R.styleable.TextViewStableSize_iconWidthDp)) {
                iconWidthDp = a.getInt(R.styleable.TextViewStableSize_iconWidthDp, 0);
            }
            a.recycle();
        }
    }

    public static int dpToPx(float dp, Context context) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics());
    }
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int wPaddingAndIcon = this.getPaddingLeft() + this.getPaddingRight() + this.dpToPx(iconWidthDp * SCALE, getContext());

        Rect bounds = new Rect();
        getPaint().getTextBounds(this.mMinText,0, mMinText.length(), bounds);
        int minTextWidth = bounds.width() + wPaddingAndIcon;

        this.seenMaxWidth = Math.max( this.seenMaxWidth, minTextWidth);
        this.seenMaxWidth = Math.max( this.seenMaxWidth, getMeasuredWidth());

        this.setMinWidth( this.seenMaxWidth );
    }


}
