/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.bluetooth.le_scan;

import static java.util.Objects.requireNonNull;

import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.IBluetoothScan;
import android.bluetooth.le.IPeriodicAdvertisingCallback;
import android.bluetooth.le.IScannerCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.AttributionSource;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.WorkSource;
import android.text.format.DateUtils;
import android.util.Log;

import com.android.bluetooth.btservice.AdapterService;

import libcore.util.HexEncoding;

import java.util.List;

public class ScanController {
    private static final String TAG = ScanController.class.getSimpleName();

    public final TransitionalScanHelper mTransitionalScanHelper;

    private final BluetoothScanBinder mBinder;

    private boolean mIsAvailable;

    private volatile boolean mTestModeEnabled = false;
    private final Looper mMainLooper;
    private Handler mTestModeHandler;
    private final Object mTestModeLock = new Object();

    /** Example raw beacons captured from a Blue Charm BC011 */
    private static final String[] TEST_MODE_BEACONS =
            new String[] {
                "020106",
                "0201060303AAFE1716AAFE10EE01626C7565636861726D626561636F6E730009168020691E0EFE13551109426C7565436861726D5F313639363835000000",
                "0201060303AAFE1716AAFE00EE626C7565636861726D31000000000001000009168020691E0EFE13551109426C7565436861726D5F313639363835000000",
                "0201060303AAFE1116AAFE20000BF017000008874803FB93540916802069080EFE13551109426C7565436861726D5F313639363835000000000000000000",
                "0201061AFF4C000215426C7565436861726D426561636F6E730EFE1355C509168020691E0EFE13551109426C7565436861726D5F31363936383500000000",
            };

    public ScanController(AdapterService adapterService) {
        mTransitionalScanHelper =
                new TransitionalScanHelper(requireNonNull(adapterService), () -> mTestModeEnabled);
        mMainLooper = adapterService.getMainLooper();
        mBinder = new BluetoothScanBinder(this);
        mIsAvailable = true;
        HandlerThread thread = new HandlerThread("BluetoothScanManager");
        thread.start();
        mTransitionalScanHelper.start(thread.getLooper());
    }

    public void stop() {
        Log.d(TAG, "stop()");
        mIsAvailable = false;
        mBinder.clearScanController();
        mTransitionalScanHelper.stop();
        mTransitionalScanHelper.cleanup();
    }

    /** Notify Scan manager of bluetooth profile connection state changes */
    public void notifyProfileConnectionStateChange(int profile, int fromState, int toState) {
        mTransitionalScanHelper.notifyProfileConnectionStateChange(profile, fromState, toState);
    }

    public TransitionalScanHelper getTransitionalScanHelper() {
        return mTransitionalScanHelper;
    }

    public IBinder getBinder() {
        return mBinder;
    }

    public void setTestModeEnabled(boolean enableTestMode) {
        synchronized (mTestModeLock) {
            if (mTestModeHandler == null) {
                mTestModeHandler =
                        new Handler(mMainLooper) {
                            public void handleMessage(Message msg) {
                                synchronized (mTestModeLock) {
                                    if (!mTestModeEnabled) {
                                        return;
                                    }
                                    for (String test : TEST_MODE_BEACONS) {
                                        mTransitionalScanHelper.onScanResultInternal(
                                                0x1b,
                                                0x1,
                                                "DD:34:02:05:5C:4D",
                                                1,
                                                0,
                                                0xff,
                                                127,
                                                -54,
                                                0x0,
                                                HexEncoding.decode(test),
                                                "DD:34:02:05:5C:4E");
                                    }
                                    sendEmptyMessageDelayed(0, DateUtils.SECOND_IN_MILLIS);
                                }
                            }
                        };
            }
            if (enableTestMode == mTestModeEnabled) {
                return;
            }
            mTestModeEnabled = enableTestMode;
            mTestModeHandler.removeMessages(0);
            mTestModeHandler.sendEmptyMessageDelayed(
                    0, enableTestMode ? DateUtils.SECOND_IN_MILLIS : 0);
        }
    }

    static class BluetoothScanBinder extends IBluetoothScan.Stub {
        private ScanController mScanController;

        BluetoothScanBinder(ScanController scanController) {
            mScanController = scanController;
        }

        @Override
        public void registerScanner(
                IScannerCallback callback,
                WorkSource workSource,
                AttributionSource attributionSource) {
            ScanController mScanController = getScanController();
            if (mScanController == null) {
                return;
            }
            mScanController
                    .getTransitionalScanHelper()
                    .registerScanner(callback, workSource, attributionSource);
        }

        @Override
        public void unregisterScanner(int scannerId, AttributionSource attributionSource) {
            ScanController mScanController = getScanController();
            if (mScanController == null) {
                return;
            }
            mScanController
                    .getTransitionalScanHelper()
                    .unregisterScanner(scannerId, attributionSource);
        }

        @Override
        public void startScan(
                int scannerId,
                ScanSettings settings,
                List<ScanFilter> filters,
                AttributionSource attributionSource) {
            ScanController mScanController = getScanController();
            if (mScanController == null) {
                return;
            }
            mScanController
                    .getTransitionalScanHelper()
                    .startScan(scannerId, settings, filters, attributionSource);
        }

        @Override
        public void startScanForIntent(
                PendingIntent intent,
                ScanSettings settings,
                List<ScanFilter> filters,
                AttributionSource attributionSource) {
            ScanController mScanController = getScanController();
            if (mScanController == null) {
                return;
            }
            mScanController
                    .getTransitionalScanHelper()
                    .registerPiAndStartScan(intent, settings, filters, attributionSource);
        }

        @Override
        public void stopScan(int scannerId, AttributionSource attributionSource) {
            ScanController mScanController = getScanController();
            if (mScanController == null) {
                return;
            }
            mScanController.getTransitionalScanHelper().stopScan(scannerId, attributionSource);
        }

        @Override
        public void stopScanForIntent(PendingIntent intent, AttributionSource attributionSource) {
            ScanController mScanController = getScanController();
            if (mScanController == null) {
                return;
            }
            mScanController.getTransitionalScanHelper().stopScan(intent, attributionSource);
        }

        @Override
        public void flushPendingBatchResults(int scannerId, AttributionSource attributionSource) {
            ScanController mScanController = getScanController();
            if (mScanController == null) {
                return;
            }
            mScanController
                    .getTransitionalScanHelper()
                    .flushPendingBatchResults(scannerId, attributionSource);
        }

        @Override
        public void registerSync(
                ScanResult scanResult,
                int skip,
                int timeout,
                IPeriodicAdvertisingCallback callback,
                AttributionSource attributionSource) {
            ScanController mScanController = getScanController();
            if (mScanController == null) {
                return;
            }
            mScanController
                    .getTransitionalScanHelper()
                    .registerSync(scanResult, skip, timeout, callback, attributionSource);
        }

        @Override
        public void unregisterSync(
                IPeriodicAdvertisingCallback callback, AttributionSource attributionSource) {
            ScanController mScanController = getScanController();
            if (mScanController == null) {
                return;
            }
            mScanController.getTransitionalScanHelper().unregisterSync(callback, attributionSource);
        }

        @Override
        public void transferSync(
                BluetoothDevice bda,
                int serviceData,
                int syncHandle,
                AttributionSource attributionSource) {
            ScanController mScanController = getScanController();
            if (mScanController == null) {
                return;
            }
            mScanController
                    .getTransitionalScanHelper()
                    .transferSync(bda, serviceData, syncHandle, attributionSource);
        }

        @Override
        public void transferSetInfo(
                BluetoothDevice bda,
                int serviceData,
                int advHandle,
                IPeriodicAdvertisingCallback callback,
                AttributionSource attributionSource) {
            ScanController mScanController = getScanController();
            if (mScanController == null) {
                return;
            }
            mScanController
                    .getTransitionalScanHelper()
                    .transferSetInfo(bda, serviceData, advHandle, callback, attributionSource);
        }

        @Override
        public int numHwTrackFiltersAvailable(AttributionSource attributionSource) {
            ScanController mScanController = getScanController();
            if (mScanController == null) {
                return 0;
            }
            return mScanController
                    .getTransitionalScanHelper()
                    .numHwTrackFiltersAvailable(attributionSource);
        }

        private void clearScanController() {
            mScanController = null;
        }

        private ScanController getScanController() {
            ScanController controller = mScanController;
            if (controller != null && controller.mIsAvailable) {
                return controller;
            }
            Log.e(TAG, "getScanController() - ScanController requested, but not available!");
            return null;
        }
    }
}
