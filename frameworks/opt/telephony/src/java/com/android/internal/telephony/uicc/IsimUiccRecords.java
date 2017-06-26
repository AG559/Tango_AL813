/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.internal.telephony.uicc;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.SystemProperties;
import android.telephony.Rlog;
import android.content.Intent;


import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.IccUtils;
import com.android.internal.telephony.gsm.SimTlv;
//import com.android.internal.telephony.gsm.VoiceMailConstants;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;

import static com.android.internal.telephony.uicc.IccConstants.EF_DOMAIN;
import static com.android.internal.telephony.uicc.IccConstants.EF_IMPI;
import static com.android.internal.telephony.uicc.IccConstants.EF_IMPU;
import static com.android.internal.telephony.uicc.IccConstants.EF_IST;
import static com.android.internal.telephony.uicc.IccConstants.EF_PCSCF;

import com.mediatek.internal.telephony.uicc.IsimServiceTable;

/**
 * {@hide}
 */
public final class IsimUiccRecords extends IccRecords implements IsimRecords {
    protected static final String LOG_TAG = "IsimUiccRecords";

    private static final boolean DBG = true;
    private static final boolean DUMP_RECORDS = true;   // Note: PII is logged when this is true
    public static final String INTENT_ISIM_REFRESH = "com.android.intent.isim_refresh";

    private static final int EVENT_APP_READY = 1;
    private static final int EVENT_ISIM_REFRESH = 31;
    private static final int EVENT_AKA_AUTHENTICATE_DONE          = 90;

    //MTK-START
    private static final int EVENT_GET_GBABP_DONE = 200;
    private static final int EVENT_GET_GBANL_DONE = 201;
    private static final int EVENT_GET_PSISMSC_DONE = 202;

    protected UiccController mUiccController;
    IsimServiceTable mIsimServiceTable;

    private int mSlotId;
    private int mIsimChannel;

    byte[] mEfPsismsc = null;
    //MTK-END

    // ISIM EF records (see 3GPP TS 31.103)
    private String mIsimImpi;               // IMS private user identity
    private String mIsimDomain;             // IMS home network domain name
    private String[] mIsimImpu;             // IMS public user identity(s)
    private String mIsimIst;             // IMS Service Table
    private String[] mIsimPcscf;             // IMS Proxy Call Session Control Function
    private String auth_rsp;

    //MTK-START
    private String mIsimGbabp;
    private String[] mIsimGbanl;
    ArrayList<byte[]> mEfGbanlList;
    //MTK-END

    private final Object mLock = new Object();

    private static final int TAG_ISIM_VALUE = 0x80;     // From 3GPP TS 31.103

    @Override
    public String toString() {
        return "IsimUiccRecords: " + super.toString()
                + " mIsimImpi=" + mIsimImpi
                + " mIsimDomain=" + mIsimDomain
                + " mIsimImpu=" + mIsimImpu
                + " mIsimIst=" + mIsimIst
                + " mIsimPcscf=" + mIsimPcscf;
    }

    public IsimUiccRecords(UiccCardApplication app, Context c, CommandsInterface ci) {
        super(app, c, ci);

        mRecordsRequested = false;  // No load request is made till SIM ready

        // recordsToLoad is set to 0 because no requests are made yet
        mRecordsToLoad = 0;
        // Start off by setting empty state
        resetRecords();
        mCi.registerForIccRefresh(this, EVENT_ISIM_REFRESH, null);

        mParentApp.registerForReady(this, EVENT_APP_READY, null);
        if (DBG) log("IsimUiccRecords X ctor this=" + this);
        mSlotId = app.getSlotId();
        mUiccController = UiccController.getInstance();
    }

    @Override
    public void dispose() {
        log("Disposing " + this);
        //Unregister for all events
        mCi.unregisterForIccRefresh(this);
        mParentApp.unregisterForReady(this);
        resetRecords();
        super.dispose();
    }

    // ***** Overridden from Handler
    public void handleMessage(Message msg) {
        AsyncResult ar;
        byte data[];
        boolean isRecordLoadResponse = false;
        if (mDestroyed.get()) {
            Rlog.e(LOG_TAG, "Received message " + msg +
                    "[" + msg.what + "] while being destroyed. Ignoring.");
            return;
        }
        loge("IsimUiccRecords: handleMessage " + msg + "[" + msg.what + "] ");

        try {
            switch (msg.what) {
                case EVENT_APP_READY:
                    onReady();
                    break;

                case EVENT_ISIM_REFRESH:
                    ar = (AsyncResult)msg.obj;
                    loge("ISim REFRESH(EVENT_ISIM_REFRESH) with exception: " + ar.exception);
                    if (ar.exception == null) {
                        Intent intent = new Intent(INTENT_ISIM_REFRESH);
                        loge("send ISim REFRESH: " + INTENT_ISIM_REFRESH);
                        mContext.sendBroadcast(intent);
                        handleIsimRefresh((IccRefreshResponse)ar.result);
                    }
                    break;

                case EVENT_AKA_AUTHENTICATE_DONE:
                    ar = (AsyncResult)msg.obj;
                    log("EVENT_AKA_AUTHENTICATE_DONE");
                    if (ar.exception != null) {
                        log("Exception ISIM AKA: " + ar.exception);
                        break;
                    } else {
                        try {
                            auth_rsp = (String)ar.result;
                            log("ISIM AKA: auth_rsp = " + auth_rsp);
                        } catch (Exception e) {
                            log("Failed to parse ISIM AKA contents: " + e);
                        }
                    }
                    synchronized (mLock) {
                        mLock.notifyAll();
                    }

                    break;

                case EVENT_GET_GBABP_DONE:
                    isRecordLoadResponse = true;
                    ar = (AsyncResult) msg.obj;
                    if (ar.exception == null) {
                       data = ((byte[]) ar.result);
                       mIsimGbabp = IccUtils.bytesToHexString(data);
                       if (DUMP_RECORDS) log("EF_ISIM_GBABP=" + mIsimGbabp);
                    } else {
                        loge("Error on GET_ISIM_GBABP with exp " + ar.exception);
                    }
                    break;

                case EVENT_GET_GBANL_DONE:
                    isRecordLoadResponse = true;
                    ar = (AsyncResult) msg.obj;
                    if (ar.exception == null) {
                       mEfGbanlList = ((ArrayList<byte[]>) ar.result);
                        if (DBG) log("GET_ISIM_GBANL record count: " + mEfGbanlList.size());
                    } else {
                        loge("Error on GET_ISIM_GBANL with exp " + ar.exception);
                    }
                    break;
                case EVENT_GET_PSISMSC_DONE:
                    isRecordLoadResponse = true;

                    ar = (AsyncResult) msg.obj;
                    data = (byte[]) ar.result;

                    if (ar.exception != null) {
                        break;
                    }

                    log("EF_PSISMSC: " + IccUtils.bytesToHexString(data));

                    if (data != null) {
                        mEfPsismsc = data;
                    }
                    break;
                default:
                    super.handleMessage(msg);   // IccRecords handles generic record load responses

            }
        } catch (RuntimeException exc) {
            // I don't want these exceptions to be fatal
            Rlog.w(LOG_TAG, "Exception parsing SIM record", exc);
        } finally {
            // Count up record load responses even if they are fails
            if (isRecordLoadResponse) {
                onRecordLoaded();
            }
        }
    }

    protected void fetchIsimRecords() {
        mRecordsRequested = true;

        mIsimChannel = mUiccController.getIccApplicationChannel(mSlotId, UiccController.APP_FAM_IMS);
        log("mIsimChannel = " + mIsimChannel);

        mFh.loadEFTransparentEx(EF_IMPI, mIsimChannel, obtainMessage(
                IccRecords.EVENT_GET_ICC_RECORD_DONE, new EfIsimImpiLoaded()));
        mRecordsToLoad++;

        mFh.loadEFLinearFixedAllEx(EF_IMPU, mIsimChannel, obtainMessage(
                IccRecords.EVENT_GET_ICC_RECORD_DONE, new EfIsimImpuLoaded()));
        mRecordsToLoad++;

        mFh.loadEFTransparentEx(EF_DOMAIN, mIsimChannel, obtainMessage(
                IccRecords.EVENT_GET_ICC_RECORD_DONE, new EfIsimDomainLoaded()));
        mRecordsToLoad++;

        mFh.loadEFTransparentEx(EF_IST, mIsimChannel, obtainMessage(
                    IccRecords.EVENT_GET_ICC_RECORD_DONE, new EfIsimIstLoaded()));
        mRecordsToLoad++;

        //mFh.loadEFLinearFixedAll(EF_PCSCF, obtainMessage(
        //            IccRecords.EVENT_GET_ICC_RECORD_DONE, new EfIsimPcscfLoaded()));
        //mRecordsToLoad++;

        if (DBG) log("fetchIsimRecords " + mRecordsToLoad + " requested: " + mRecordsRequested);
    }

    private void fetchGbaParam() {
        if (mIsimServiceTable.isAvailable(IsimServiceTable.IsimService.GBA)) {
            mFh.loadEFTransparentEx(EF_ISIM_GBABP, mIsimChannel, obtainMessage(EVENT_GET_GBABP_DONE));
            mRecordsToLoad++;

            mFh.loadEFLinearFixedAllEx(EF_ISIM_GBANL, mIsimChannel, obtainMessage(EVENT_GET_GBANL_DONE));
            mRecordsToLoad++;
        }
    }

    protected void resetRecords() {
        // recordsRequested is set to false indicating that the SIM
        // read requests made so far are not valid. This is set to
        // true only when fresh set of read requests are made.
        mIsimImpi = null;
        mIsimDomain = null;
        mIsimImpu = null;
        mIsimIst = null;
        mIsimPcscf = null;
        auth_rsp = null;

        mRecordsRequested = false;
    }

    private class EfIsimImpiLoaded implements IccRecords.IccRecordLoaded {
        public String getEfName() {
            return "EF_ISIM_IMPI";
        }
        public void onRecordLoaded(AsyncResult ar) {
            byte[] data = (byte[]) ar.result;
            mIsimImpi = isimTlvToString(data);
            if (DUMP_RECORDS) log("EF_IMPI=" + mIsimImpi);
        }
    }

    private class EfIsimImpuLoaded implements IccRecords.IccRecordLoaded {
        public String getEfName() {
            return "EF_ISIM_IMPU";
        }
        public void onRecordLoaded(AsyncResult ar) {
            ArrayList<byte[]> impuList = (ArrayList<byte[]>) ar.result;
            if (DBG) log("EF_IMPU record count: " + impuList.size());
            mIsimImpu = new String[impuList.size()];
            int i = 0;
            for (byte[] identity : impuList) {
                String impu = isimTlvToString(identity);
                if (DUMP_RECORDS) log("EF_IMPU[" + i + "]=" + impu);
                mIsimImpu[i++] = impu;
            }
        }
    }

    private class EfIsimDomainLoaded implements IccRecords.IccRecordLoaded {
        public String getEfName() {
            return "EF_ISIM_DOMAIN";
        }
        public void onRecordLoaded(AsyncResult ar) {
            byte[] data = (byte[]) ar.result;
            mIsimDomain = isimTlvToString(data);
            if (DUMP_RECORDS) log("EF_DOMAIN=" + mIsimDomain);
        }
    }

    private class EfIsimIstLoaded implements IccRecords.IccRecordLoaded {
        public String getEfName() {
            return "EF_ISIM_IST";
        }
        public void onRecordLoaded(AsyncResult ar) {
            byte[] data = (byte[]) ar.result;
            mIsimIst = IccUtils.bytesToHexString(data);
            if (DUMP_RECORDS) log("EF_IST=" + mIsimIst);


            mIsimServiceTable = new IsimServiceTable(data);
            if (DBG) log("IST: " + mIsimServiceTable);

            mIsimChannel = mUiccController.getIccApplicationChannel(mSlotId, UiccController.APP_FAM_IMS);
            log("mIsimChannel = " + mIsimChannel);

            if (mIsimServiceTable.isAvailable(IsimServiceTable.IsimService.PCSCF_ADDRESS)
                    || mIsimServiceTable.isAvailable(IsimServiceTable.IsimService.PCSCF_DISCOVERY)) {
                mFh.loadEFLinearFixedAllEx(EF_PCSCF, mIsimChannel, obtainMessage(
                        IccRecords.EVENT_GET_ICC_RECORD_DONE, new EfIsimPcscfLoaded()));
                mRecordsToLoad++;
            }

            if (mIsimServiceTable.isAvailable(IsimServiceTable.IsimService.SM_OVER_IP)) {
                mFh.loadEFLinearFixedEx(EF_PSISMSC, 1, mIsimChannel, obtainMessage(EVENT_GET_PSISMSC_DONE));
                mRecordsToLoad++;
            }

            fetchGbaParam();
        }
    }

    private class EfIsimPcscfLoaded implements IccRecords.IccRecordLoaded {
        public String getEfName() {
            return "EF_ISIM_PCSCF";
        }
        public void onRecordLoaded(AsyncResult ar) {
            ArrayList<byte[]> pcscflist = (ArrayList<byte[]>) ar.result;
            if (DBG) log("EF_PCSCF record count: " + pcscflist.size());
            mIsimPcscf = new String[pcscflist.size()];
            int i = 0;
            for (byte[] identity : pcscflist) {
                String pcscf = isimTlvToString(identity);
                if (SystemProperties.get("ro.mtk_tc1_feature").equals("1")) {  
                    if (DUMP_RECORDS) log("EF_PCSCF[" + i + "]=" + pcscf);
                    mIsimPcscf[i++] = pcscf;                    
                }
                else {
                    // FIXME: address type should not be encoded?
                    mIsimPcscf[i] = ((pcscf != null) ? pcscf.substring(1, pcscf.length()) : "");
                    if (DUMP_RECORDS) log("EF_PCSCF[" + i + "]=" + mIsimPcscf[i]);
                    i++;
                }
            }
        }
    }

    /**
     * ISIM records for IMS are stored inside a Tag-Length-Value record as a UTF-8 string
     * with tag value 0x80.
     * @param record the byte array containing the IMS data string
     * @return the decoded String value, or null if the record can't be decoded
     */
    private static String isimTlvToString(byte[] record) {
        SimTlv tlv = new SimTlv(record, 0, record.length);
        do {
            if (tlv.getTag() == TAG_ISIM_VALUE) {
                return new String(tlv.getData(), Charset.forName("UTF-8"));
            }
        } while (tlv.nextObject());

        Rlog.e(LOG_TAG, "[ISIM] can't find TLV tag in ISIM record, returning null");
        return null;
    }

    @Override
    protected void onRecordLoaded() {
        // One record loaded successfully or failed, In either case
        // we need to update the recordsToLoad count
        mRecordsToLoad -= 1;
        if (DBG) log("onRecordLoaded " + mRecordsToLoad + " requested: " + mRecordsRequested);

        if (mRecordsToLoad == 0 && mRecordsRequested == true) {
            onAllRecordsLoaded();
        } else if (mRecordsToLoad < 0) {
            loge("recordsToLoad <0, programmer error suspected");
            mRecordsToLoad = 0;
        }
    }

    @Override
    protected void onAllRecordsLoaded() {
       if (DBG) log("record load complete");
        mRecordsLoadedRegistrants.notifyRegistrants(
                new AsyncResult(null, null, null));
    }

    private void handleFileUpdate(int efid) {
        switch (efid) {
            case EF_IMPI:
                mFh.loadEFTransparentEx(EF_IMPI, mIsimChannel, obtainMessage(
                            IccRecords.EVENT_GET_ICC_RECORD_DONE, new EfIsimImpiLoaded()));
                mRecordsToLoad++;
                break;

            case EF_IMPU:
                mFh.loadEFLinearFixedAllEx(EF_IMPU, mIsimChannel, obtainMessage(
                            IccRecords.EVENT_GET_ICC_RECORD_DONE, new EfIsimImpuLoaded()));
                mRecordsToLoad++;
            break;

            case EF_DOMAIN:
                mFh.loadEFTransparentEx(EF_DOMAIN, mIsimChannel, obtainMessage(
                            IccRecords.EVENT_GET_ICC_RECORD_DONE, new EfIsimDomainLoaded()));
                mRecordsToLoad++;
            break;

            case EF_IST:
                mFh.loadEFTransparentEx(EF_IST, mIsimChannel, obtainMessage(
                            IccRecords.EVENT_GET_ICC_RECORD_DONE, new EfIsimIstLoaded()));
                mRecordsToLoad++;
            break;

            case EF_PCSCF:
                mFh.loadEFLinearFixedAllEx(EF_PCSCF, mIsimChannel, obtainMessage(
                            IccRecords.EVENT_GET_ICC_RECORD_DONE, new EfIsimPcscfLoaded()));
                mRecordsToLoad++;

            default:
                fetchIsimRecords();
                break;
        }
    }

    private void handleIsimRefresh(IccRefreshResponse refreshResponse) {
        if (refreshResponse == null) {
            if (DBG) log("handleIsimRefresh received without input");
            return;
        }

        if (refreshResponse.aid != null &&
                !refreshResponse.aid.equals(mParentApp.getAid())) {
            // This is for different app. Ignore.
            if (DBG) log("handleIsimRefresh received different app");
            return;
        }

        switch (refreshResponse.refreshResult) {
            case IccRefreshResponse.REFRESH_RESULT_FILE_UPDATE:
                if (DBG) log("handleIsimRefresh with REFRESH_RESULT_FILE_UPDATE");
                for (int i = 0; i < refreshResponse.efId.length; i++) {
                    handleFileUpdate(refreshResponse.efId[i]);
                }
                break;

            case IccRefreshResponse.REFRESH_RESULT_INIT:
                if (DBG) log("handleIsimRefresh with REFRESH_RESULT_INIT");
                // need to reload all files (that we care about)
                // onIccRefreshInit();
                fetchIsimRecords();
                break;

            case IccRefreshResponse.REFRESH_RESULT_RESET:
                // Refresh reset is handled by the UiccCard object.
                if (DBG) log("handleIsimRefresh with REFRESH_RESULT_RESET");
                break;

            default:
                // unknown refresh operation
                if (DBG) log("handleIsimRefresh with unknown operation");
                break;
        }
    }

    @Override
    public void registerForRecordsLoaded(Handler h, int what, Object obj) {
        if (mDestroyed.get()) {
            return;
        }

        Registrant r = new Registrant(h, what, obj);
        mRecordsLoadedRegistrants.add(r);

        if (mRecordsToLoad == 0 && mRecordsRequested == true) {
            r.notifyRegistrant(new AsyncResult(null, null, null));
        }
    }

    @Override
    public void unregisterForRecordsLoaded(Handler h) {
        mRecordsLoadedRegistrants.remove(h);
    }

    /**
     * Return the IMS private user identity (IMPI).
     * Returns null if the IMPI hasn't been loaded or isn't present on the ISIM.
     * @return the IMS private user identity string, or null if not available
     */
    @Override
    public String getIsimImpi() {
        return mIsimImpi;
    }

    /**
     * Return the IMS home network domain name.
     * Returns null if the IMS domain hasn't been loaded or isn't present on the ISIM.
     * @return the IMS home network domain name, or null if not available
     */
    @Override
    public String getIsimDomain() {
        return mIsimDomain;
    }

    /**
     * Return an array of IMS public user identities (IMPU).
     * Returns null if the IMPU hasn't been loaded or isn't present on the ISIM.
     * @return an array of IMS public user identity strings, or null if not available
     */
    @Override
    public String[] getIsimImpu() {
        return (mIsimImpu != null) ? mIsimImpu.clone() : null;
    }

    /**
     * Returns the IMS Service Table (IST) that was loaded from the ISIM.
     * @return IMS Service Table or null if not present or not loaded
     */
    @Override
    public String getIsimIst() {
        return mIsimIst;
    }

    /**
     * Returns the IMS Proxy Call Session Control Function(PCSCF) that were loaded from the ISIM.
     * @return an array of  PCSCF strings with one PCSCF per string, or null if
     *      not present or not loaded
     */
    @Override
    public String[] getIsimPcscf() {
        return (mIsimPcscf != null) ? mIsimPcscf.clone() : null;
    }

    /**
     * Returns the response of ISIM Authetification through RIL.
     * Returns null if the Authentification hasn't been successed or isn't present iphonesubinfo.
     * @return the response of ISIM Authetification, or null if not available
     */
    @Override
    public String getIsimChallengeResponse(String nonce){
        if (DBG) log("getIsimChallengeResponse-nonce:"+nonce);
        try {
            synchronized(mLock) {
                mCi.requestIsimAuthentication(nonce,obtainMessage(EVENT_AKA_AUTHENTICATE_DONE));
                try {
                    mLock.wait();
                } catch (InterruptedException e) {
                    log("interrupted while trying to request Isim Auth");
                }
            }
        } catch(Exception e) {
            if (DBG) log( "Fail while trying to request Isim Auth");
            return null;
        }

        if (DBG) log("getIsimChallengeResponse-auth_rsp"+auth_rsp);

        return auth_rsp;
    }

    public byte[] getEfPsismsc() {
        log("PSISMSC = " + mEfPsismsc);
        return mEfPsismsc;
    }


    /**
     * Returns the GBA bootstrapping parameters (GBABP) that was loaded from the ISIM.
     * @return GBA bootstrapping parameters or null if not present or not loaded
     */
    public String getIsimGbabp() {
        log("ISIM GBABP = " + mIsimGbabp);
        return mIsimGbabp;
    }

    /**
     * Set the GBA bootstrapping parameters (GBABP) value into the ISIM.
     * @param gbabp a GBA bootstrapping parameters value in String type
     * @param onComplete
     *        onComplete.obj will be an AsyncResult
     *        ((AsyncResult)onComplete.obj).exception == null on success
     *        ((AsyncResult)onComplete.obj).exception != null on fail
     */
    public void setIsimGbabp(String gbabp, Message onComplete) {
        byte[] data = IccUtils.hexStringToBytes(gbabp);

        mIsimChannel = mUiccController.getIccApplicationChannel(mSlotId, UiccController.APP_FAM_IMS);
        log("mIsimChannel = " + mIsimChannel);

        mFh.updateEFTransparentEx(EF_ISIM_GBABP, mIsimChannel, data, onComplete);
    }


    @Override
    public int getDisplayRule(String plmn) {
        // Not applicable to Isim
        return 0;
    }

    @Override
    public void onReady() {
        fetchIsimRecords();
    }

    @Override
    public void onRefresh(boolean fileChanged, int[] fileList) {
        if (fileChanged) {
            // A future optimization would be to inspect fileList and
            // only reload those files that we care about.  For now,
            // just re-fetch all SIM records that we cache.
            fetchIsimRecords();
        }
    }

    @Override
    public void setVoiceMailNumber(String alphaTag, String voiceNumber,
            Message onComplete) {
        // Not applicable to Isim
    }

    @Override
    public void setVoiceMessageWaiting(int line, int countWaiting) {
        // Not applicable to Isim
    }

    @Override
    protected void log(String s) {
        if (DBG) Rlog.d(LOG_TAG, "[ISIM] " + s);
    }

    @Override
    protected void loge(String s) {
        if (DBG) Rlog.e(LOG_TAG, "[ISIM] " + s);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("IsimRecords: " + this);
        pw.println(" extends:");
        super.dump(fd, pw, args);
        pw.println(" mIsimImpi=" + mIsimImpi);
        pw.println(" mIsimDomain=" + mIsimDomain);
        pw.println(" mIsimImpu[]=" + Arrays.toString(mIsimImpu));
        pw.println(" mIsimIst" + mIsimIst);
        pw.println(" mIsimPcscf"+mIsimPcscf);
        pw.flush();
    }

    @Override
    public int getVoiceMessageCount() {
        return 0; // Not applicable to Isim
    }

}
