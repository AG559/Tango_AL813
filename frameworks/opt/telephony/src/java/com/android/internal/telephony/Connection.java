/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony;

import android.net.Uri;
import android.os.SystemClock;
import android.telecom.ConferenceParticipant;
import android.telephony.Rlog;
import android.util.Log;

import java.lang.Override;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * {@hide}
 */
public abstract class Connection {
    public interface PostDialListener {
        void onPostDialWait();
        void onPostDialChar(char c);
    }

    /**
     * Listener interface for events related to the connection which should be reported to the
     * {@link android.telecom.Connection}.
     */
    public interface Listener {
        public void onVideoStateChanged(int videoState);
        public void onLocalVideoCapabilityChanged(boolean capable);
        public void onRemoteVideoCapabilityChanged(boolean capable);
        public void onVideoProviderChanged(
                android.telecom.Connection.VideoProvider videoProvider);
        public void onAudioQualityChanged(int audioQuality);
        public void onConferenceParticipantsChanged(List<ConferenceParticipant> participants);
        /// M: For VoLTE conference. @{
        /**
         * For VoLTE enhanced conference call, notify invite conf. participants completed.
         * @param isSuccess is success or not.
         * @hide
         */
        public void onConferenceParticipantsInvited(boolean isSuccess);

        /**
         * For VoLTE conference SRVCC, notify the new participant connections in GsmPhone.
         * @param radioConnections the participant connections in GsmPhone
         * @hide
         */
        public void onConferenceConnectionsConfigured(ArrayList<Connection> radioConnections);
        /// @}

        /// M: Notify Telecom to remove pending action by onActionFailed(). @{
        public void onSuppServiceFailed(int actionCode);
        /// @}

        /// M: WFC call type update. @{
        /**
         * To notify that call type is VoLTE or ViWiFi.
         * @param callType the call type of current call
         * @hide
         */
        public void onCallTypeChanged(int callType);
        /// @}
    }

    /**
     * Base listener implementation.
     */
    public abstract static class ListenerBase implements Listener {
        @Override
        public void onVideoStateChanged(int videoState) {}
        @Override
        public void onLocalVideoCapabilityChanged(boolean capable) {}
        @Override
        public void onRemoteVideoCapabilityChanged(boolean capable) {}
        @Override
        public void onVideoProviderChanged(
                android.telecom.Connection.VideoProvider videoProvider) {}
        @Override
        public void onAudioQualityChanged(int audioQuality) {}
        @Override
        public void onConferenceParticipantsChanged(List<ConferenceParticipant> participants) {}
        /// M: For VoLTE conference. @{

        // for enhanced conference call
        @Override
        public void onConferenceParticipantsInvited(boolean isSuccess) {}

        // for conference SRVCC.
        @Override
        public void onConferenceConnectionsConfigured(ArrayList<Connection> radioConnections) {}
        /// @}

        /// M: Notify Telecom to remove pending action by onActionFailed(). @{
        @Override
        public void onSuppServiceFailed(int actionCode) {}
        /// @}

        /// M: WFC call type update. @{
        @Override
        public void onCallTypeChanged(int callType) {}
        /// @}
    }

    public static final int AUDIO_QUALITY_STANDARD = 1;
    public static final int AUDIO_QUALITY_HIGH_DEFINITION = 2;

    //Caller Name Display
    protected String mCnapName;
    protected int mCnapNamePresentation  = PhoneConstants.PRESENTATION_ALLOWED;
    protected String mAddress;     // MAY BE NULL!!!
    protected String mDialString;          // outgoing calls only
    protected int mNumberPresentation = PhoneConstants.PRESENTATION_ALLOWED;
    protected boolean mIsIncoming;
    /*
     * These time/timespan values are based on System.currentTimeMillis(),
     * i.e., "wall clock" time.
     */
    protected long mCreateTime;
    protected long mConnectTime;
    /*
     * These time/timespan values are based on SystemClock.elapsedRealTime(),
     * i.e., time since boot.  They are appropriate for comparison and
     * calculating deltas.
     */
    protected long mConnectTimeReal;
    protected long mDuration;
    protected long mHoldingStartTime;  // The time when the Connection last transitioned
                            // into HOLDING
    protected Connection mOrigConnection;
    private List<PostDialListener> mPostDialListeners = new ArrayList<>();
    public Set<Listener> mListeners = new CopyOnWriteArraySet<>();

    protected boolean mNumberConverted = false;
    protected String mConvertedNumber;

    private static String LOG_TAG = "Connection";

    Object mUserData;
    private int mVideoState;
    private boolean mLocalVideoCapable;
    private boolean mRemoteVideoCapable;
    private int mAudioQuality;
    private android.telecom.Connection.VideoProvider mVideoProvider;
    public Call.State mPreHandoverState = Call.State.IDLE;

    /// M: for Ims Conference SRVCC. @{
    public boolean mPreMultipartyState = false;
    /// @}

    /// M: CC018: Redirecting number via COLP @{
    String mRedirectingAddress;
    /// @}
    /// M: CC017: Forwarding number via EAIC @{
    String mForwardingAddress;
    /// @}

    /// M: WFC call type update. @{
    /**
     * To store call type of the current connection.
     */
    private int mCallType = 0;
    /// @}

    /* Instance Methods */

    /**
     * Gets address (e.g. phone number) associated with connection.
     * TODO: distinguish reasons for unavailability
     *
     * @return address or null if unavailable
     */

    public String getAddress() {
        return mAddress;
    }

    /// M: CC016: number presentation via CLIP @{
    public void setNumberPresentation(int num) {
        mNumberPresentation = num;
    }
    /// @}

    /**
     * Gets CNAP name associated with connection.
     * @return cnap name or null if unavailable
     */
    public String getCnapName() {
        return mCnapName;
    }

    /// M: CC010: Add RIL interface @{
    public void setCnapName(String cnapName) {
        this.mCnapName = cnapName;
    }
    /// @}

    /**
     * Get original dial string.
     * @return original dial string or null if unavailable
     */
    public String getOrigDialString(){
        return null;
    }

    /**
     * Gets CNAP presentation associated with connection.
     * @return cnap name or null if unavailable
     */

    public int getCnapNamePresentation() {
       return mCnapNamePresentation;
    }

    /**
     * @return Call that owns this Connection, or null if none
     */
    public abstract Call getCall();

    /**
     * Connection create time in currentTimeMillis() format
     * Basically, set when object is created.
     * Effectively, when an incoming call starts ringing or an
     * outgoing call starts dialing
     */
    public long getCreateTime() {
        return mCreateTime;
    }

    /**
     * Connection connect time in currentTimeMillis() format.
     * For outgoing calls: Begins at (DIALING|ALERTING) -> ACTIVE transition.
     * For incoming calls: Begins at (INCOMING|WAITING) -> ACTIVE transition.
     * Returns 0 before then.
     */
    public long getConnectTime() {
        return mConnectTime;
    }

    /**
     * Sets the Connection connect time in currentTimeMillis() format.
     *
     * @param connectTime the new connect time.
     */
    public void setConnectTime(long connectTime) {
        mConnectTime = connectTime;
    }

    /**
     * Connection connect time in elapsedRealtime() format.
     * For outgoing calls: Begins at (DIALING|ALERTING) -> ACTIVE transition.
     * For incoming calls: Begins at (INCOMING|WAITING) -> ACTIVE transition.
     * Returns 0 before then.
     */
    public long getConnectTimeReal() {
        return mConnectTimeReal;
    }

    /**
     * Disconnect time in currentTimeMillis() format.
     * The time when this Connection makes a transition into ENDED or FAIL.
     * Returns 0 before then.
     */
    public abstract long getDisconnectTime();

    /**
     * Returns the number of milliseconds the call has been connected,
     * or 0 if the call has never connected.
     * If the call is still connected, then returns the elapsed
     * time since connect.
     */
    public long getDurationMillis() {
        if (mConnectTimeReal == 0) {
            return 0;
        } else if (mDuration == 0) {
            return SystemClock.elapsedRealtime() - mConnectTimeReal;
        } else {
            return mDuration;
        }
    }

    /**
     * The time when this Connection last transitioned into HOLDING
     * in elapsedRealtime() format.
     * Returns 0, if it has never made a transition into HOLDING.
     */
    public long getHoldingStartTime() {
        return mHoldingStartTime;
    }

    /**
     * If this connection is HOLDING, return the number of milliseconds
     * that it has been on hold for (approximately).
     * If this connection is in any other state, return 0.
     */

    public abstract long getHoldDurationMillis();

    /**
     * Returns call disconnect cause. Values are defined in
     * {@link android.telephony.DisconnectCause}. If the call is not yet
     * disconnected, NOT_DISCONNECTED is returned.
     */
    public abstract int getDisconnectCause();

    /**
     * Returns true of this connection originated elsewhere
     * ("MT" or mobile terminated; another party called this terminal)
     * or false if this call originated here (MO or mobile originated).
     */
    public boolean isIncoming() {
        return mIsIncoming;
    }

    /**
     * If this Connection is connected, then it is associated with
     * a Call.
     *
     * Returns getCall().getState() or Call.State.IDLE if not
     * connected
     */
    public Call.State getState() {
        Call c;

        c = getCall();

        if (c == null) {
            return Call.State.IDLE;
        } else {
            return c.getState();
        }
    }

    /**
     * If this connection went through handover return the state of the
     * call that contained this connection before handover.
     */
    public Call.State getStateBeforeHandover() {
        return mPreHandoverState;
    }

    /// M: for Ims Conference SRVCC. @{
    /**
     * If this connection went through handover return the isMultiparty state
     *  of the call that contained this connection before handover.
     * @return boolean is multiparty or not.
     * @hide
     */
    public boolean isMultipartyBeforeHandover() {
        return mPreMultipartyState;
    }
    /// @}

    /**
     * isAlive()
     *
     * @return true if the connection isn't disconnected
     * (could be active, holding, ringing, dialing, etc)
     */
    public boolean
    isAlive() {
        return getState().isAlive();
    }

    /**
     * Returns true if Connection is connected and is INCOMING or WAITING
     */
    public boolean
    isRinging() {
        return getState().isRinging();
    }

    /**
     *
     * @return the userdata set in setUserData()
     */
    public Object getUserData() {
        return mUserData;
    }

    /**
     *
     * @param userdata user can store an any userdata in the Connection object.
     */
    public void setUserData(Object userdata) {
        mUserData = userdata;
    }

    /**
     * Hangup individual Connection
     */
    public abstract void hangup() throws CallStateException;

    /**
     * Hangup individual Connection for RingingConn -Add by mtk01411 [ALPS00475147]
     */
    public void hangup(int discRingingConnCause) throws CallStateException {
        //just add default implementation
    }

    /**
     * Separate this call from its owner Call and assigns it to a new Call
     * (eg if it is currently part of a Conference call
     * TODO: Throw exception? Does GSM require error display on failure here?
     */
    public abstract void separate() throws CallStateException;

    public enum PostDialState {
        NOT_STARTED,    /* The post dial string playback hasn't
                           been started, or this call is not yet
                           connected, or this is an incoming call */
        STARTED,        /* The post dial string playback has begun */
        WAIT,           /* The post dial string playback is waiting for a
                           call to proceedAfterWaitChar() */
        WILD,           /* The post dial string playback is waiting for a
                           call to proceedAfterWildChar() */
        COMPLETE,       /* The post dial string playback is complete */
        CANCELLED,       /* The post dial string playback was cancelled
                           with cancelPostDial() */
        PAUSE           /* The post dial string playback is pausing for a
                           call to processNextPostDialChar*/
    }

    public void clearUserData(){
        mUserData = null;
    }

    public final void addPostDialListener(PostDialListener listener) {
        if (!mPostDialListeners.contains(listener)) {
            mPostDialListeners.add(listener);
        }
    }

    protected final void clearPostDialListeners() {
        mPostDialListeners.clear();
    }

    protected final void notifyPostDialListeners() {
        if (getPostDialState() == PostDialState.WAIT) {
            for (PostDialListener listener : new ArrayList<>(mPostDialListeners)) {
                listener.onPostDialWait();
            }
        }
    }

    protected final void notifyPostDialListenersNextChar(char c) {
        for (PostDialListener listener : new ArrayList<>(mPostDialListeners)) {
            listener.onPostDialChar(c);
        }
    }

    public abstract PostDialState getPostDialState();

    /**
     * Returns the portion of the post dial string that has not
     * yet been dialed, or "" if none
     */
    public abstract String getRemainingPostDialString();

    /**
     * See Phone.setOnPostDialWaitCharacter()
     */

    public abstract void proceedAfterWaitChar();

    /**
     * See Phone.setOnPostDialWildCharacter()
     */
    public abstract void proceedAfterWildChar(String str);
    /**
     * Cancel any post
     */
    public abstract void cancelPostDial();

    /**
     * Returns the caller id presentation type for incoming and waiting calls
     * @return one of PRESENTATION_*
     */
    public abstract int getNumberPresentation();

    /**
     * Returns the User to User Signaling (UUS) information associated with
     * incoming and waiting calls
     * @return UUSInfo containing the UUS userdata.
     */
    public abstract UUSInfo getUUSInfo();

    /**
     * Returns the CallFail reason provided by the RIL with the result of
     * RIL_REQUEST_LAST_CALL_FAIL_CAUSE
     */
    public abstract int getPreciseDisconnectCause();

    /**
     * Returns the original Connection instance associated with
     * this Connection
     */
    public Connection getOrigConnection() {
        return mOrigConnection;
    }

    /**
     * Returns whether the original ImsPhoneConnection was a member
     * of a conference call
     * @return valid only when getOrigConnection() is not null
     */
    public abstract boolean isMultiparty();

    public void migrateFrom(Connection c) {
        if (c == null) return;
        mListeners = c.mListeners;
        mAddress = c.getAddress();
        mNumberPresentation = c.getNumberPresentation();
        mDialString = c.getOrigDialString();
        mCnapName = c.getCnapName();
        mCnapNamePresentation = c.getCnapNamePresentation();
        mIsIncoming = c.isIncoming();
        mCreateTime = c.getCreateTime();
        mConnectTime = c.getConnectTime();
        mConnectTimeReal = c.getConnectTimeReal();
        mHoldingStartTime = c.getHoldingStartTime();
        mOrigConnection = c.getOrigConnection();
    }

    /**
     * Assign a listener to be notified of state changes.
     *
     * @param listener A listener.
     */
    public final void addListener(Listener listener) {
        mListeners.add(listener);
    }

    /**
     * Removes a listener.
     *
     * @param listener A listener.
     */
    public final void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    /**
     * Returns the current video state of the connection.
     *
     * @return The video state of the connection.
     */
    public int getVideoState() {
        return mVideoState;
    }

    /**
     * Returns the local video capability state for the connection.
     *
     * @return {@code True} if the connection has local video capabilities.
     */
    public boolean isLocalVideoCapable() {
        return mLocalVideoCapable;
    }

    /**
     * Returns the remote video capability state for the connection.
     *
     * @return {@code True} if the connection has remote video capabilities.
     */
    public boolean isRemoteVideoCapable() {
        return mRemoteVideoCapable;
    }

    /**
     * Returns the {@link android.telecom.Connection.VideoProvider} for the connection.
     *
     * @return The {@link android.telecom.Connection.VideoProvider}.
     */
    public android.telecom.Connection.VideoProvider getVideoProvider() {
        return mVideoProvider;
    }

    /**
     * Returns the audio-quality for the connection.
     *
     * @return The audio quality for the connection.
     */
    public int getAudioQuality() {
        return mAudioQuality;
    }

    /**
     * Sets the videoState for the current connection and reports the changes to all listeners.
     * Valid video states are defined in {@link android.telecom.VideoProfile}.
     *
     * @return The video state.
     */
    public void setVideoState(int videoState) {
        mVideoState = videoState;
        for (Listener l : mListeners) {
            l.onVideoStateChanged(mVideoState);
        }
    }

    /**
     * Sets whether video capability is present locally.
     *
     * @param capable {@code True} if video capable.
     */
    public void setLocalVideoCapable(boolean capable) {
        mLocalVideoCapable = capable;
        for (Listener l : mListeners) {
            l.onLocalVideoCapabilityChanged(mLocalVideoCapable);
        }
    }

    /**
     * Sets whether video capability is present remotely.
     *
     * @param capable {@code True} if video capable.
     */
    public void setRemoteVideoCapable(boolean capable) {
        mRemoteVideoCapable = capable;
        for (Listener l : mListeners) {
            l.onRemoteVideoCapabilityChanged(mRemoteVideoCapable);
        }
    }

    /**
     * Set the audio quality for the connection.
     *
     * @param audioQuality The audio quality.
     */
    public void setAudioQuality(int audioQuality) {
        mAudioQuality = audioQuality;
        for (Listener l : mListeners) {
            l.onAudioQualityChanged(mAudioQuality);
        }
    }

    /**
     * Sets the {@link android.telecom.Connection.VideoProvider} for the connection.
     *
     * @param videoProvider The video call provider.
     */
    public void setVideoProvider(android.telecom.Connection.VideoProvider videoProvider) {
        mVideoProvider = videoProvider;
        for (Listener l : mListeners) {
            l.onVideoProviderChanged(mVideoProvider);
        }
    }

    public void setConverted(String oriNumber) {
        mNumberConverted = true;
        mConvertedNumber = mAddress;
        mAddress = oriNumber;
        mDialString = oriNumber;
    }

    /**
     * Notifies listeners of a change to conference participant(s).
     *
     * @param conferenceParticipants The participant(s).
     */
    public void updateConferenceParticipants(List<ConferenceParticipant> conferenceParticipants) {
        for (Listener l : mListeners) {
            l.onConferenceParticipantsChanged(conferenceParticipants);
        }
    }

    /**
     * Notifies this Connection of a request to disconnect a participant of the conference managed
     * by the connection.
     *
     * @param endpoint the {@link Uri} of the participant to disconnect.
     */
    public void onDisconnectConferenceParticipant(Uri endpoint) {
    }

    /// M: For VoLTE conference. @{
    /**
     * Notify when the task of onInviteConferenceParticipants() is completed.
     * @param isSuccess is success or not.
     * @hide
     */
    public void notifyConferenceParticipantsInvited(boolean isSuccess) {
        for (Listener l : mListeners) {
            l.onConferenceParticipantsInvited(isSuccess);
        }
    }

    /**
     * Notify when the new participant connections in GsmPhone are maded.
     * @param radioConnections new participant connections in GsmPhone
     * @hide
     */
    public void notifyConferenceConnectionsConfigured(ArrayList<Connection> radioConnections) {
        for (Listener l : mListeners) {
            l.onConferenceConnectionsConfigured(radioConnections);
        }
    }
    /// @}

    /**
     * Build a human representation of a connection instance, suitable for debugging.
     * Don't log personal stuff unless in debug mode.
     * @return a string representing the internal state of this connection.
     */
    public String toString() {
        StringBuilder str = new StringBuilder(128);

        if (Rlog.isLoggable(LOG_TAG, Log.DEBUG)) {
            str.append("addr: " + getAddress())
                    .append(" pres.: " + getNumberPresentation())
                    .append(" dial: " + getOrigDialString())
                    .append(" postdial: " + getRemainingPostDialString())
                    .append(" cnap name: " + getCnapName())
                    .append("(" + getCnapNamePresentation() + ")");
        }
        str.append(" incoming: " + isIncoming())
                .append(" state: " + getState())
                .append(" post dial state: " + getPostDialState());
        return str.toString();
    }

    /// M: CC018: Redirecting number via COLP @{
    /**
     * Gets redirecting address (e.g. phone number) associated with connection.
     *
     * @return address or null if unavailable
    */
    public String getRedirectingAddress() {
       return mRedirectingAddress;
    }

    /**
     * Sets redirecting address (e.g. phone number) associated with connection.
     *
    */
    public void setRedirectingAddress(String address) {
        mRedirectingAddress = address;
    }
    /// @}

    /// M: CC017: Forwarding number via EAIC @{
    /**
     * Gets forwarding address (e.g. phone number) associated with connection.
     * A makes call to B and B redirects(Forwards) this call to C, the forwarding address is B.
     * @return address or null if unavailable
    */
    public String getForwardingAddress() {
       return mForwardingAddress;
    }
	
    /** 
     * Sets forwarding address (e.g. phone number) associated with connection.
     * A makes call to B and B redirects(Forwards) this call to C, the forwarding address is B.
    */
    public void setForwardingAddress (String address) {
       mForwardingAddress = address;
    }
    /// @}

    /// M: For 3G VT only @{
    /**
     * Returns true of this connection is a vt call.
     * @internal
     */
    public boolean isVideo() {
        Rlog.d(LOG_TAG, "Connection: isVideo = false");
        return false;
    }
    /// @}

    /// M: Notify Telecom when Merge/Resume/Hold fails. @{
    /**
     * Notifies listeners when SS action fails.
     *
     * @param actionCode SS action.
     */
    public void notifySuppServiceFailed(int actionCode) {
        for (Listener l : mListeners) {
            l.onSuppServiceFailed(actionCode);
        }
    }
    /// @}

    /// M: WFC call type update. @{
    /**
     * To notify call type(VoLTE or VoWIFI).
     *
     * @param callType The call type value.
    */
    public void setCallType(int callType) {
       mCallType = callType;
       for (Listener l : mListeners) {
          l.onCallTypeChanged(callType);
       }
    }

    /**
     * Returns the call type for the connection.
     *
     * @return The call type for the connection.
     */
    public int getCallType() {
        return mCallType;
    }
    /// @}
}
