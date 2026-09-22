/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.topjohnwu.superuser.CallbackList;
import com.topjohnwu.superuser.Shell;

import java.util.LinkedList;
import java.util.List;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.twoyi.utils.ShellUtil;

/**
 * @author weishu
 * @date 2022/1/1.
 */

public class BootLogTexture extends TextureView implements TextureView.SurfaceTextureListener {

    private final AtomicBoolean mRendering = new AtomicBoolean(false);

    private final LimitedQueue<String> mLogMessages = new LimitedQueue<>(400);
    private final LinkedList<String> mSnapShot = new LinkedList<>();

    private final SparseArray<Paint> mPaints = new SparseArray<>();
    private final Paint mDefaultPaint = new Paint();

    private static final SparseIntArray COLOR_MAP = new SparseIntArray();

    static {
        COLOR_MAP.put('V', 0xBBBBBB);
        COLOR_MAP.put('D', 0x5EBB1E);
        COLOR_MAP.put('I', 0x4CBBA2);
        COLOR_MAP.put('W', 0xFFD21C);
        COLOR_MAP.put('E', 0xFF6B68);
        COLOR_MAP.put('F', Color.RED);
        COLOR_MAP.put('S', Color.WHITE);

//        COLOR_MAP.put('V', 0xFFFFFF);
//        COLOR_MAP.put('D', 0x5FAFFE);
//        COLOR_MAP.put('I', 0x02D701);
//        COLOR_MAP.put('W', 0xD75E02);
//        COLOR_MAP.put('E', 0xFF2600);
//        COLOR_MAP.put('F', 0xFF2600);
//        COLOR_MAP.put('S', Color.WHITE);
    }

    public BootLogTexture(@NonNull Context context) {
        this(context, null);
    }

    public BootLogTexture(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BootLogTexture(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public BootLogTexture(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context);
    }

    private void init(Context context) {
        setSurfaceTextureListener(this);

        for (int i = 0; i < COLOR_MAP.size(); i++) {
            int key = COLOR_MAP.keyAt(i);
            int value = COLOR_MAP.valueAt(i);

            Paint paint = new Paint();
            setPaint(paint, value);

            mPaints.put(key, paint);
        }

        setPaint(mDefaultPaint, Color.WHITE);
    }

    private void setPaint(Paint paint, int color) {
        paint.setColor(color);
        paint.setAntiAlias(true);
        paint.setTextSize(16);
        paint.setAlpha(128);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mRendering.set(true);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mRendering.set(false);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);

        mRendering.set(visibility == VISIBLE);
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {

        Shell.EXECUTOR.execute(() -> {
            List<String> callbackList = new CallbackList<String>() {
                @Override
                public void onAddElement(String s) {
                    if (TextUtils.isEmpty(s)) {
                        return;
                    }
                    synchronized (mLogMessages) {
                        mLogMessages.add(s);
                    }
                }
            };

            addMessage("I [twoyi] boot console attached");

            // Keep logcat alive for the whole boot instead of stopping after
            // 30 seconds.  Verbose output is useful here because early loader
            // and linker diagnostics are exactly what a failed guest needs.
            Shell shell = ShellUtil.newSh();
            shell.newJob().add("logcat -v brief '*:V'").to(callbackList).submit();

            Thread guestLog = new Thread(this::followGuestLog, "guest-boot-log");
            guestLog.setDaemon(true);
            guestLog.start();

            while (mRendering.get()) {
                render();
                SystemClock.sleep(16);
            }

            try {
                shell.waitAndClose(1, TimeUnit.SECONDS);
            } catch (Throwable ignored) {
            }

        });
    }

    private void addMessage(String message) {
        if (TextUtils.isEmpty(message)) {
            return;
        }
        synchronized (mLogMessages) {
            mLogMessages.add(message);
        }
    }

    /** Follow stdout/stderr produced by rootfs/init.  logcat alone misses the
     * loader, linker and init-parser failures that happen before logd exists. */
    private void followGuestLog() {
        File file = new File(getContext().getDataDir(), "log.txt");
        long position = 0;
        while (mRendering.get()) {
            if (!file.isFile()) {
                SystemClock.sleep(50);
                continue;
            }
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                if (input.length() < position) {
                    position = 0;
                }
                input.seek(position);
                String line;
                while (mRendering.get() && (line = input.readLine()) != null) {
                    addMessage("I [guest] " + line);
                }
                position = input.getFilePointer();
            } catch (Throwable ignored) {
            }
            SystemClock.sleep(50);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {

    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
        mRendering.set(false);
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {

    }

    private void render() {
        Canvas canvas = null;
        try {
            canvas = lockCanvas();

            // clear canvas
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

            mSnapShot.clear();

            // TODO: lock free
            synchronized (mLogMessages) {
                mSnapShot.addAll(mLogMessages);
            }

            int lineHeight = 20;
            int visibleLines = Math.max(1, getHeight() / lineHeight);
            int first = Math.max(0, mSnapShot.size() - visibleLines);
            int count = 1;
            for (int index = first; index < mSnapShot.size(); index++) {
                String log = mSnapShot.get(index);

                char chr = log.charAt(0);

                Paint paint = mPaints.get(chr);
                if (paint == null) {
                    paint = mDefaultPaint;
                }

                canvas.drawText(log, 0, count++ * lineHeight, paint);
            }

        } finally {
            if (canvas != null) {
                unlockCanvasAndPost(canvas);
            }
        }

    }
}
