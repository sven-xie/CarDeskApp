package com.sven.cardesk.utils;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.WindowManager;
import android.view.WindowMetrics;

public class DisplayUtils {

    public static int carDpi = 480;
    public static float carDensity = 3.0F;

    /**
     * px转dip或dp
     *
     * @param pxValue
     * @return
     */
    public static int px2dip(float pxValue) {
        float scale = Utils.getContext().getResources().getDisplayMetrics().density;
        return (int) (pxValue / scale + 0.5f);
    }

    /**
     * dip或dp转px
     *
     * @param dipValue
     * @return
     */
    public static int dip2px(float dipValue) {
        float scale = Utils.getContext().getResources().getDisplayMetrics().density;
        return (int) (dipValue * scale + 0.5f);
    }


    public static int dip2px(Context context, float dipValue) {
        float scale = context.getResources().getDisplayMetrics().density;
        return (int) (dipValue * scale + 0.5f);
    }

    /**
     * dip or dp to px, keeping the fractional part.
     */
    public static float dip2pxF(float dipValue) {
        return dipValue * Utils.getContext().getResources().getDisplayMetrics().density;
    }

    /**
     * px转sp
     *
     * @param context
     * @param pxValue
     * @return
     */
    public static int px2sp(float pxValue) {
        float fontScale = Utils.getContext().getResources().getDisplayMetrics().scaledDensity;
        return (int) (pxValue / fontScale + 0.5f);
    }


    /**
     * sp转px
     *
     * @param spValue
     * @return
     */
    public static int sp2px(float spValue) {
        float fontScale = Utils.getContext().getResources().getDisplayMetrics().scaledDensity;
        return (int) (spValue * fontScale + 0.5);
    }

    public static int getScreenWidth() {
        try {
            WindowManager windowManager = (WindowManager) Utils.getContext().getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) {
                return Utils.getContext().getResources().getDisplayMetrics().widthPixels;
            }
            Point point = new Point();
            windowManager.getDefaultDisplay().getRealSize(point);
            return point.x;
        } catch (Exception e) {
        }
        return 0;
    }

    public static int getScreenHeightWithoutNavBar() {
        try {
            WindowManager windowManager = (WindowManager) Utils.getContext().getSystemService(Context.WINDOW_SERVICE);
            Display defaultDisplay = windowManager.getDefaultDisplay();
            //获取内容高度
            DisplayMetrics outMetrics2 = new DisplayMetrics();
            defaultDisplay.getMetrics(outMetrics2);
            int heightPixels2 = outMetrics2.heightPixels;
            return heightPixels2;
        } catch (Exception e) {
        }
        return 0;
    }

    /**
     * 获取屏幕的高度(单位：px)
     *
     * @return 屏幕高
     */
    public static int getScreenHeight() {
        try {
            WindowManager windowManager = (WindowManager) Utils.getContext().getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) {
                return Utils.getContext().getResources().getDisplayMetrics().heightPixels;
            }
            Point point = new Point();
            windowManager.getDefaultDisplay().getRealSize(point);
            return point.y;
        } catch (Exception e) {
        }
        return 0;
    }

    /**
     * 获取状态栏高度
     *
     * @return
     */
    public static int getStatusBarHeight() {
        int result = 0;
        //获取状态栏高度的资源id
        int resourceId = Utils.getContext().getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            result = Utils.getContext().getResources().getDimensionPixelSize(resourceId);
        }
        return result;
    }


    /**
     * 获得屏幕真实高度（包含底部导航栏）
     */
    public static int getScreenRealHeight() {
        WindowManager windowManager = (WindowManager) Utils.getContext().getSystemService(Context.WINDOW_SERVICE);
        final Display display = windowManager.getDefaultDisplay();
        Point outPoint = new Point();
        if (Build.VERSION.SDK_INT >= 19) {
            // 可能有虚拟按键的情况
            display.getRealSize(outPoint);
        } else {
            // 不可能有虚拟按键
            display.getSize(outPoint);
        }
        int mRealSizeHeight;//手机屏幕真实高度
        mRealSizeHeight = outPoint.y;
        return mRealSizeHeight;
    }


    public static Point getScreenRealWidthHeight() {
        WindowManager windowManager = (WindowManager) Utils.getContext().getSystemService(Context.WINDOW_SERVICE);
        Point size = new Point();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11 及以上版本
            WindowMetrics windowMetrics = windowManager.getCurrentWindowMetrics();
            Rect bounds = windowMetrics.getBounds();
            size.x = bounds.width();
            size.y = bounds.height();
        } else {
            // Android 4.2 至 Android 10
            Display display = windowManager.getDefaultDisplay();
            display.getRealSize(size);
        }
        return size;
    }


    /**
     * 获取屏幕相关数据
     *
     * @return
     */
    public static int getScreenDpi() {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager windowManager = (WindowManager) Utils.getContext().getSystemService(Context.WINDOW_SERVICE);
        windowManager.getDefaultDisplay().getMetrics(metrics);
        return metrics.densityDpi;
    }

    public static float getScreenDensity() {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager windowManager = (WindowManager) Utils.getContext().getSystemService(Context.WINDOW_SERVICE);
        windowManager.getDefaultDisplay().getMetrics(metrics);
        return metrics.density;
    }

    public static DisplayMetrics getScreenMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager windowManager = (WindowManager) Utils.getContext().getSystemService(Context.WINDOW_SERVICE);
        windowManager.getDefaultDisplay().getMetrics(metrics);
        return metrics;
    }


    public static int getNavigationBarHeight(Context context) {
        Resources resources = context.getResources();
        int resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android");
        if (resourceId > 0) {
            return resources.getDimensionPixelSize(resourceId);
        }
        return 0;
    }


    public static boolean isLand() {
        return DisplayUtils.getScreenWidth() > DisplayUtils.getScreenRealHeight();
    }


    /**
     * 计算文本宽度
     *
     * @param context  上下文对象
     * @param text     要测量的文本
     * @param textSize 字体大小（单位：dp）
     * @return 文本宽度（单位：px）
     */
    public static float getTextWidth(String text, float textSize) {
        // 1. 创建 Paint 对象
        Paint paint = new Paint();

        // 2. 将 dp 转换为 px
        float textSizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                textSize,
                Utils.getContext().getResources().getDisplayMetrics()
        );

        // 3. 设置字体大小（px）
        paint.setTextSize(textSizePx);

        // 4. 测量文本宽度
        return paint.measureText(text);
    }


    public static int dip2px(float dipValue, float dpi) {
        return (int) (dipValue * dpi + 0.5f);
    }


    public static int dip2px(float dipValue, int baseDpi, int phoneDpi) {
        float dpi = dipValue * baseDpi / phoneDpi;
        return (int) DisplayUtils.dip2px(dpi);
    }

    public static int dip2pxForPhoneWholeCast(float dipValue, float density) {
        return (int) (dipValue * density + 0.5f);
    }

    public static int dip2pxForPhoneWholeCast(float dipValue) {
        return (int) (dipValue * carDensity + 0.5f);
    }
}