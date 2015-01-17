/*
 * Copyright (C) 2014 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui;

import com.android.systemui.statusbar.policy.BatteryController;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.widget.TextView;

import com.android.internal.util.slim.ColorHelper;
import com.android.systemui.cm.UserContentObserver;
import cyanogenmod.providers.CMSettings;

import java.text.NumberFormat;

public class BatteryLevelTextView extends TextView implements
        BatteryController.BatteryStateChangeCallback{

    private static final int DEFAULT_BATTERY_TEXT_COLOR = 0xffffffff;

    private BatteryController mBatteryController;
    private boolean mBatteryCharging;
    private boolean mForceShow;
    private boolean mAttached;
    private int mRequestedVisibility;
    private int mBatteryLevel = 0;
    private int mNewColor;
    private int mOldColor;
    private Animator mColorTransitionAnimator;

    private int mStyle;
    private int mPercentMode;

    private ContentResolver mResolver;

    private SettingsObserver mObserver;

    private class SettingsObserver extends UserContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        protected void observe() {
            super.observe();

            mResolver.registerContentObserver(CMSettings.System.getUriFor(
                    CMSettings.System.STATUS_BAR_BATTERY_STATUS_TEXT_COLOR),
                    false, this, UserHandle.USER_ALL);
        }

        @Override
        protected void unobserve() {
            super.unobserve();

            getContext().getContentResolver().unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri.equals(CMSettings.System.getUriFor(
                    CMSettings.System.STATUS_BAR_BATTERY_STATUS_TEXT_COLOR))) {
                update();
            }
        }

        @Override
        public void update() {
            setTextColor(false);
            updateVisibility();
        }
    };

    public BatteryLevelTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mResolver = context.getContentResolver();
        mRequestedVisibility = getVisibility();

        mNewColor = CMSettings.System.getInt(mResolver,
            CMSettings.System.STATUS_BAR_BATTERY_STATUS_TEXT_COLOR,
            DEFAULT_BATTERY_TEXT_COLOR);
        mOldColor = mNewColor;
        mColorTransitionAnimator = createColorTransitionAnimator(0, 1);
        mObserver = new SettingsObserver(new Handler());

        // setBatteryStateRegistar (if called) will made the view visible and ready to be hidden
        // if the view shouldn't be displayed. Otherwise this view should be hidden from start.
        mRequestedVisibility = getVisibility();
    }

    public void setForceShown(boolean forceShow) {
        mForceShow = forceShow;
        updateVisibility();
    }

    public void setBatteryController(BatteryController batteryController) {
        mBatteryController = batteryController;
        if (mAttached) {
            mBatteryController.addStateChangedCallback(this);
        }
    }

    @Override
    public void setVisibility(int visibility) {
        mRequestedVisibility = visibility;
        updateVisibility();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Respect font size setting.
        setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.battery_level_text_size));
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        String percentage = NumberFormat.getPercentInstance().format((double) level / 100.0);
        mBatteryLevel = level;
        setText(percentage);
        if (mBatteryCharging != charging) {
            mBatteryCharging = charging;
            updateVisibility();
        }
    }

    @Override
    public void onPowerSaveChanged() {
        // Not used
    }

    @Override
    public void onBatteryStyleChanged(int style, int percentMode) {
        mStyle = style;
        mPercentMode = percentMode;
        updateVisibility();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (mBatteryController != null) {
            mBatteryController.addStateChangedCallback(this);
        }
        mObserver.observe();

        mAttached = true;
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mAttached = false;
        mResolver.unregisterContentObserver(mObserver);

        if (mBatteryController != null) {
            mBatteryController.removeStateChangedCallback(this);
        }
    }

    public void updateVisibility() {
        boolean showNextPercent =
            mStyle != BatteryController.STYLE_GONE && (
                (mPercentMode == BatteryController.PERCENTAGE_MODE_OUTSIDE) ||
                (mBatteryCharging && mPercentMode ==
                    BatteryController.PERCENTAGE_MODE_INSIDE));
        if (mStyle == BatteryController.STYLE_TEXT) {
            showNextPercent = true;
        } else if (mPercentMode == BatteryController.PERCENTAGE_MODE_OFF ||
                mStyle == BatteryController.STYLE_GONE) {
            showNextPercent = false;
        }

        if (showNextPercent || mForceShow) {
            super.setVisibility(mRequestedVisibility);
        } else {
            super.setVisibility(GONE);
        }
    }

    public void setTextColor(boolean isHeader) {
        if (!isHeader) {
            mNewColor = CMSettings.System.getInt(mResolver,
                CMSettings.System.STATUS_BAR_BATTERY_STATUS_TEXT_COLOR,
                DEFAULT_BATTERY_TEXT_COLOR);
            if (!mBatteryCharging && mBatteryLevel > 16) {
                if (mOldColor != mNewColor) {
                    mColorTransitionAnimator.start();
                }
            }
            setTextColor(mNewColor);
        }
    }

    private ValueAnimator createColorTransitionAnimator(float start, float end) {
        ValueAnimator animator = ValueAnimator.ofFloat(start, end);

        animator.setDuration(500);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener(){
            @Override public void onAnimationUpdate(ValueAnimator animation) {
                float position = animation.getAnimatedFraction();
                int blended = ColorHelper.getBlendColor(mOldColor, mNewColor, position);
                setTextColor(blended);
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mOldColor = mNewColor;
            }
        });
        return animator;
    }

}
