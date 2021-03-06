/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein is
 * confidential and proprietary to MediaTek Inc. and/or its licensors. Without
 * the prior written permission of MediaTek inc. and/or its licensors, any
 * reproduction, modification, use or disclosure of MediaTek Software, and
 * information contained herein, in whole or in part, shall be strictly
 * prohibited.
 * 
 * MediaTek Inc. (C) 2014. All rights reserved.
 * 
 * BY OPENING THIS FILE, RECEIVER HEREBY UNEQUIVOCALLY ACKNOWLEDGES AND AGREES
 * THAT THE SOFTWARE/FIRMWARE AND ITS DOCUMENTATIONS ("MEDIATEK SOFTWARE")
 * RECEIVED FROM MEDIATEK AND/OR ITS REPRESENTATIVES ARE PROVIDED TO RECEIVER
 * ON AN "AS-IS" BASIS ONLY. MEDIATEK EXPRESSLY DISCLAIMS ANY AND ALL
 * WARRANTIES, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE OR
 * NONINFRINGEMENT. NEITHER DOES MEDIATEK PROVIDE ANY WARRANTY WHATSOEVER WITH
 * RESPECT TO THE SOFTWARE OF ANY THIRD PARTY WHICH MAY BE USED BY,
 * INCORPORATED IN, OR SUPPLIED WITH THE MEDIATEK SOFTWARE, AND RECEIVER AGREES
 * TO LOOK ONLY TO SUCH THIRD PARTY FOR ANY WARRANTY CLAIM RELATING THERETO.
 * RECEIVER EXPRESSLY ACKNOWLEDGES THAT IT IS RECEIVER'S SOLE RESPONSIBILITY TO
 * OBTAIN FROM ANY THIRD PARTY ALL PROPER LICENSES CONTAINED IN MEDIATEK
 * SOFTWARE. MEDIATEK SHALL ALSO NOT BE RESPONSIBLE FOR ANY MEDIATEK SOFTWARE
 * RELEASES MADE TO RECEIVER'S SPECIFICATION OR TO CONFORM TO A PARTICULAR
 * STANDARD OR OPEN FORUM. RECEIVER'S SOLE AND EXCLUSIVE REMEDY AND MEDIATEK'S
 * ENTIRE AND CUMULATIVE LIABILITY WITH RESPECT TO THE MEDIATEK SOFTWARE
 * RELEASED HEREUNDER WILL BE, AT MEDIATEK'S OPTION, TO REVISE OR REPLACE THE
 * MEDIATEK SOFTWARE AT ISSUE, OR REFUND ANY SOFTWARE LICENSE FEES OR SERVICE
 * CHARGE PAID BY RECEIVER TO MEDIATEK FOR SUCH MEDIATEK SOFTWARE AT ISSUE.
 *
 * The following software/firmware and/or related documentation ("MediaTek
 * Software") have been modified by MediaTek Inc. All revisions are subject to
 * any receiver's applicable license agreements with MediaTek Inc.
 */

package com.mediatek.camera.v2.aaa.exposure;

import java.util.ArrayList;
import java.util.Map;

import com.mediatek.camera.v2.aaa.IAaaManager.IAaaListener;
import com.mediatek.camera.v2.module.ModuleListener.CaptureType;
import com.mediatek.camera.v2.module.ModuleListener.RequestType;
import com.mediatek.camera.v2.setting.ISettingServant;
import com.mediatek.camera.v2.setting.ISettingServant.ISettingChangedListener;
import com.mediatek.camera.v2.setting.SettingConvertor;
import com.mediatek.camera.v2.util.SettingKeys;

import android.app.Activity;
import android.graphics.RectF;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.CaptureRequest.Builder;
import android.hardware.camera2.TotalCaptureResult;
import android.util.Log;
import android.view.ViewGroup;

public class AutoExposure implements IExposure, ISettingChangedListener {
    private final String           TAG = AutoExposure.class.getSimpleName();
    private final String           FLASH_ON = "on";
    private final String           FLASH_AUTO = "auto";
    private final IAaaListener     mIaaaListener;
    private final ISettingServant  mSettingServant;
    private Activity               mActivity;
    private ArrayList<String>      mCaredSettingChangedKeys = new ArrayList<String>();
    
    private int                    mAEMode = CameraMetadata.CONTROL_AE_MODE_ON;
    private int                    mFlashMode = CameraMetadata.FLASH_MODE_OFF;
    private String                 mExposureCompensation = null;
    private boolean                mNeedAePretrigger = false;
    private boolean                mAePreTriggerAndCaptureEnabled = false;
    private boolean                mAePreTriggerRequestProcessed = false;
    
    public AutoExposure(ISettingServant settingServant, IAaaListener aaaListener) {
        mIaaaListener = aaaListener;
        mSettingServant = settingServant;
    }

    @Override
    public void open(Activity activity, ViewGroup parentView,
            boolean isCaptureIntent) {
        mActivity = activity;
        updateCaredSettingChangedKeys();
        mSettingServant.registerSettingChangedListener(this,mCaredSettingChangedKeys,
                ISettingChangedListener.MIDDLE_PRIORITY);
    }

    @Override
    public void resume() {
        mAEMode = CameraMetadata.CONTROL_AE_MODE_ON;
        mFlashMode = CameraMetadata.FLASH_MODE_OFF;
        mExposureCompensation = null;
        mNeedAePretrigger = false;
        mAePreTriggerAndCaptureEnabled = false;
        mAePreTriggerRequestProcessed = false;
    }

    @Override
    public void pause() {
        
    }

    @Override
    public void close() {
        mSettingServant.unRegisterSettingChangedListener(this);
    }

    @Override
    public void onPreviewAreaChanged(RectF previewArea) {
        
    }

    @Override
    public void onSingleTapUp(float x, float y) {
        
    }

    @Override
    public void configuringSessionRequest(RequestType requestType, 
            Builder requestBuilder, boolean bottomCamera) {
        Log.i(TAG, "[configuringSessionRequests]+ mAePretriggerRequested:" + mNeedAePretrigger);
        updateExposureCompensation();
        updateAeFlashMode();
        requestBuilder.set(CaptureRequest.FLASH_MODE, mFlashMode);
        requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, mAEMode);
        if (mExposureCompensation != null && mAEMode != CameraMetadata.CONTROL_AE_MODE_OFF) {
            int ev = SettingConvertor.getNativeValue(SettingKeys.KEY_EXPOSURE, mExposureCompensation);
            requestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev);
        }
        // check and do ae pre trigger
        if (mNeedAePretrigger) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, 
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            mNeedAePretrigger = false;
        }
        Log.i(TAG, "[configuringSessionRequests]- requestType = " + requestType
                + " AEMode = " + mAEMode + " FlashMode = " + mFlashMode
                + " mExposureCompensation = " + mExposureCompensation);
    }

    @Override
    public void onPreviewCaptureStarted(CaptureRequest request, long timestamp,
            long frameNumber) {
        
    }

    @Override
    public void onPreviewCaptureCompleted(CaptureRequest request,
            TotalCaptureResult result) {
        checkAeState(request, result);
    }
    
    @Override
    public void aePreTriggerAndCapture() {
        Log.i(TAG, "[aePreTriggerAndCapture]+");
        String flash = mSettingServant.getSettingValue(SettingKeys.KEY_FLASH);
        if (!FLASH_ON.equals(flash) && !FLASH_AUTO.equals(flash)) {
            // only flash on/auto do ae pre trigger
            // else do capture immediately
            mIaaaListener.requestChangeCaptureRequets(false,RequestType.STILL_CAPTURE, CaptureType.CAPTURE);
            Log.i(TAG, "[aePreTriggerAndCapture]- flash:" + flash);
            return;
        }
        mNeedAePretrigger = true;
        mAePreTriggerAndCaptureEnabled = true;
        mIaaaListener.requestChangeCaptureRequets(true,mIaaaListener.getRepeatingRequestType(), CaptureType.CAPTURE);
        mIaaaListener.requestChangeCaptureRequets(true,mIaaaListener.getRepeatingRequestType(), CaptureType.REPEATING_REQUEST);
        Log.i(TAG, "[aePreTriggerAndCapture]-");
    }
    
    @Override
    public void onSettingChanged(Map<String, String> result) {
        String[] keys = {SettingKeys.KEY_EXPOSURE, SettingKeys.KEY_FLASH};
        String value;
        for (int i = 0;i < keys.length; i++) {
            value = result.get(keys[i]);
            if (value != null) {
                requestChangeCaptureRequets();
            }
        }
    }
    
    private void updateCaredSettingChangedKeys() {
        String[] keys = {SettingKeys.KEY_EXPOSURE, SettingKeys.KEY_FLASH};
        String key;
        for (int i = 0;i < keys.length; i++) {
            key = keys[i];
            if (key != null && !mCaredSettingChangedKeys.contains(key)) {
                mCaredSettingChangedKeys.add(key);
            }
        }
    }
    
    private void updateExposureCompensation() {
        String exposureCompensation = mSettingServant.getSettingValue(SettingKeys.KEY_EXPOSURE);
        Log.i(TAG, "[updateExposureCompensation]+ EV=" + exposureCompensation);
        if (exposureCompensation != null && !exposureCompensation.equals(mExposureCompensation)) {
            mExposureCompensation = exposureCompensation;
        }
        Log.i(TAG, "[updateExposureCompensation]- ");
    }
    
    private void updateAeFlashMode() {
        String flash = mSettingServant.getSettingValue(SettingKeys.KEY_FLASH);
        Log.i(TAG, "[updateAeFlashMode]+ Flash=" + flash);
        mFlashMode = CameraMetadata.FLASH_MODE_OFF;
        if (FLASH_ON.equalsIgnoreCase(flash)) {
            if (mIaaaListener.getRepeatingRequestType() == RequestType.RECORDING) {
                mAEMode = CameraMetadata.CONTROL_AE_MODE_ON;
                mFlashMode = CameraMetadata.FLASH_MODE_TORCH;
            } else {
                mAEMode = CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH;
            }
        } else if (FLASH_AUTO.equalsIgnoreCase(flash)) {
            mAEMode = CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH;
        } else {
            mAEMode = CameraMetadata.CONTROL_AE_MODE_ON;
        }
        Log.i(TAG, "[updateAeFlashMode]- flash:" + flash);
    }
    
    private void requestChangeCaptureRequets() {
        mIaaaListener.requestChangeCaptureRequets(false,mIaaaListener.getRepeatingRequestType(), CaptureType.REPEATING_REQUEST);
    }
    
    private void checkAeState(CaptureRequest request, TotalCaptureResult result) {
        int aePrecaptureTrigger = request.get(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER);
        int aeState = result.get(TotalCaptureResult.CONTROL_AE_STATE);
        Log.i(TAG, "aeStateCheck aeState:" + aeState + " aePrecaptureTrigger:" + aePrecaptureTrigger);
        
        if (mAePreTriggerAndCaptureEnabled) {
            if (!mAePreTriggerRequestProcessed) {
                mAePreTriggerRequestProcessed = (aePrecaptureTrigger == CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            }
            if (mAePreTriggerRequestProcessed && 
                    (aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED 
                        || aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED)) {
                mActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Log.i(TAG, "Ae pre capture trigger completed submit still capture!");
                        mIaaaListener.requestChangeCaptureRequets(false,RequestType.STILL_CAPTURE, CaptureType.CAPTURE);
                    }
                });
                mAePreTriggerAndCaptureEnabled = false;
                mAePreTriggerRequestProcessed = false;
            }
        }
    }
}
