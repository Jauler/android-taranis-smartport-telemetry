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

    //should fit this text
    private String mMinText = "";
    //width of icon and it's padding, if there is DrawableLeft icon.
    //It is to complex to calculate it, so we just provide explicit value here.
    private int iconWidthDp = 0;

    private int seenMaxWidth = 0;

    //Text and icons are drawn at this fraction of the sizes given in the layout ("Sensor size"
    //in the settings). Not initialized here: the base class sets up the icons of the layout
    //from its constructor, before the initializers of this class run. Zero stands for 1.
    private float scale;
    private float baseTextSizePx;

    public TextViewStableSize(Context context) {
        this(context, null);
    }

    public TextViewStableSize(Context context, AttributeSet attrs) {
        super(context, attrs);
        setAttributes(attrs);
        baseTextSizePx = getTextSize();
    }

    private float currentScale() {
        return scale > 0f ? scale : 1f;
    }

    /**
     * Draws the text and the icons at the given fraction of their sizes in the layout.
     */
    public void setScale(float newScale) {
        if (newScale <= 0f || newScale == currentScale()) {
            return;
        }
        scale = newScale;
        setTextSize(TypedValue.COMPLEX_UNIT_PX, baseTextSizePx * newScale);
        Drawable[] relative = getCompoundDrawablesRelative();
        if (relative[0] != null || relative[2] != null) {
            setCompoundDrawablesRelative(scaled(relative[0]), scaled(relative[1]), scaled(relative[2]), scaled(relative[3]));
        } else {
            Drawable[] absolute = getCompoundDrawables();
            setCompoundDrawables(scaled(absolute[0]), scaled(absolute[1]), scaled(absolute[2]), scaled(absolute[3]));
        }
        //the width kept so far belongs to the old size
        seenMaxWidth = 0;
        setMinWidth(0);
        requestLayout();
    }

    private Drawable scaled(Drawable drawable) {
        if (drawable != null) {
            float s = currentScale();
            drawable.setBounds(0, 0,
                    Math.round(drawable.getIntrinsicWidth() * s),
                    Math.round(drawable.getIntrinsicHeight() * s));
        }
        return drawable;
    }

    //Icons changed at run time (signal strength, battery level...) come through these two

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

        int wPaddingAndIcon = this.getPaddingLeft() + this.getPaddingRight() + this.dpToPx(iconWidthDp * currentScale(), getContext());

        Rect bounds = new Rect();
        getPaint().getTextBounds(this.mMinText,0, mMinText.length(), bounds);
        int minTextWidth = bounds.width() + wPaddingAndIcon;

        this.seenMaxWidth = Math.max( this.seenMaxWidth, minTextWidth);
        this.seenMaxWidth = Math.max( this.seenMaxWidth, getMeasuredWidth());

        // setMinWidth asks for a new layout and a redraw every time: only when it grows
        if (this.getMinWidth() != this.seenMaxWidth) {
            this.setMinWidth( this.seenMaxWidth );
        }
    }


}