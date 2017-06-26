/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.net;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.apache.http.HttpHost;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

///M: Support MoM checking @{
import android.os.Binder;

import com.mediatek.common.mom.MobileManagerUtils;
import com.mediatek.common.mom.SubPermissions;

import java.net.Socket;
import java.net.SocketCheckHandler;
import org.apache.http.client.HttpRequestCheckHandler;
import org.apache.http.impl.client.AbstractHttpClient;
///@}

import java.net.HttpNotifyHandler;
import android.os.ServiceManager;
import android.util.Slog;
import android.os.SystemProperties;


/**
 * A convenience class for accessing the user and default proxy
 * settings.
 */
public final class Proxy {

    // Set to true to enable extra debugging.
    private static final boolean DEBUG = false;
    private static final String TAG = "Proxy";

    private static final ProxySelector sDefaultProxySelector;

    /**
     * Used to notify an app that's caching the default connection proxy
     * that either the default connection or its proxy has changed.
     * The intent will have the following extra value:</p>
     * <ul>
     *   <li><em>EXTRA_PROXY_INFO</em> - The ProxyProperties for the proxy.  Non-null,
     *                                   though if the proxy is undefined the host string
     *                                   will be empty.
     * </ul>
     *
     * <p class="note">This is a protected intent that can only be sent by the system
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String PROXY_CHANGE_ACTION = "android.intent.action.PROXY_CHANGE";
    /**
     * Intent extra included with {@link #PROXY_CHANGE_ACTION} intents.
     * It describes the new proxy being used (as a {@link ProxyInfo} object).
     */
    public static final String EXTRA_PROXY_INFO = "android.intent.extra.PROXY_INFO";

    /** @hide */
    public static final int PROXY_VALID             = 0;
    /** @hide */
    public static final int PROXY_HOSTNAME_EMPTY    = 1;
    /** @hide */
    public static final int PROXY_HOSTNAME_INVALID  = 2;
    /** @hide */
    public static final int PROXY_PORT_EMPTY        = 3;
    /** @hide */
    public static final int PROXY_PORT_INVALID      = 4;
    /** @hide */
    public static final int PROXY_EXCLLIST_INVALID  = 5;

    private static ConnectivityManager sConnectivityManager = null;

    // Hostname / IP REGEX validation
    // Matches blank input, ips, and domain names
    private static final String NAME_IP_REGEX =
        "[a-zA-Z0-9]+(\\-[a-zA-Z0-9]+)*(\\.[a-zA-Z0-9]+(\\-[a-zA-Z0-9]+)*)*";

    private static final String HOSTNAME_REGEXP = "^$|^" + NAME_IP_REGEX + "$";

    private static final Pattern HOSTNAME_PATTERN;

    private static final String EXCL_REGEX =
        "[a-zA-Z0-9*]+(\\-[a-zA-Z0-9*]+)*(\\.[a-zA-Z0-9*]+(\\-[a-zA-Z0-9*]+)*)*";

    private static final String EXCLLIST_REGEXP = "^$|^" + EXCL_REGEX + "(," + EXCL_REGEX + ")*$";

    private static final Pattern EXCLLIST_PATTERN;

    static {
        HOSTNAME_PATTERN = Pattern.compile(HOSTNAME_REGEXP);
        EXCLLIST_PATTERN = Pattern.compile(EXCLLIST_REGEXP);
        sDefaultProxySelector = ProxySelector.getDefault();
    }

    /**
     * Return the proxy object to be used for the URL given as parameter.
     * @param ctx A Context used to get the settings for the proxy host.
     * @param url A URL to be accessed. Used to evaluate exclusion list.
     * @return Proxy (java.net) object containing the host name. If the
     *         user did not set a hostname it returns the default host.
     *         A null value means that no host is to be used.
     * {@hide}
     */
    public static final java.net.Proxy getProxy(Context ctx, String url) {
        String host = "";
        if ((url != null) && !isLocalHost(host)) {
            URI uri = URI.create(url);
            ProxySelector proxySelector = ProxySelector.getDefault();

            List<java.net.Proxy> proxyList = proxySelector.select(uri);

            if (proxyList.size() > 0) {
                return proxyList.get(0);
            }
        }
        return java.net.Proxy.NO_PROXY;
    }


    /**
     * Return the proxy host set by the user.
     * @param ctx A Context used to get the settings for the proxy host.
     * @return String containing the host name. If the user did not set a host
     *         name it returns the default host. A null value means that no
     *         host is to be used.
     * @deprecated Use standard java vm proxy values to find the host, port
     *         and exclusion list.  This call ignores the exclusion list.
     */
    public static final String getHost(Context ctx) {
        java.net.Proxy proxy = getProxy(ctx, null);
        if (proxy == java.net.Proxy.NO_PROXY) return null;
        try {
            return ((InetSocketAddress)(proxy.address())).getHostName();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Return the proxy port set by the user.
     * @param ctx A Context used to get the settings for the proxy port.
     * @return The port number to use or -1 if no proxy is to be used.
     * @deprecated Use standard java vm proxy values to find the host, port
     *         and exclusion list.  This call ignores the exclusion list.
     */
    public static final int getPort(Context ctx) {
        java.net.Proxy proxy = getProxy(ctx, null);
        if (proxy == java.net.Proxy.NO_PROXY) return -1;
        try {
            return ((InetSocketAddress)(proxy.address())).getPort();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Return the default proxy host specified by the carrier.
     * @return String containing the host name or null if there is no proxy for
     * this carrier.
     * @deprecated Use standard java vm proxy values to find the host, port and
     *         exclusion list.  This call ignores the exclusion list and no
     *         longer reports only mobile-data apn-based proxy values.
     */
    public static final String getDefaultHost() {
        String host = System.getProperty("http.proxyHost");
        if (TextUtils.isEmpty(host)) return null;
        return host;
    }

    /**
     * Return the default proxy port specified by the carrier.
     * @return The port number to be used with the proxy host or -1 if there is
     * no proxy for this carrier.
     * @deprecated Use standard java vm proxy values to find the host, port and
     *         exclusion list.  This call ignores the exclusion list and no
     *         longer reports only mobile-data apn-based proxy values.
     */
    public static final int getDefaultPort() {
        if (getDefaultHost() == null) return -1;
        try {
            return Integer.parseInt(System.getProperty("http.proxyPort"));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Returns the preferred proxy to be used by clients. This is a wrapper
     * around {@link android.net.Proxy#getHost()}.
     *
     * @param context the context which will be passed to
     * {@link android.net.Proxy#getHost()}
     * @param url the target URL for the request
     * @note Calling this method requires permission
     * android.permission.ACCESS_NETWORK_STATE
     * @return The preferred proxy to be used by clients, or null if there
     * is no proxy.
     * {@hide}
     */
    // TODO: Get rid of this method. It's used only in tests.
    public static final HttpHost getPreferredHttpHost(Context context,
            String url) {
        java.net.Proxy prefProxy = getProxy(context, url);

        /// M: debug logging
        Log.d(TAG, "prefProxy:" + prefProxy);

        if (prefProxy.equals(java.net.Proxy.NO_PROXY)) {
            return null;
        } else {
            InetSocketAddress sa = (InetSocketAddress)prefProxy.address();
            return new HttpHost(sa.getHostName(), sa.getPort(), "http");
        }
    }

    private static final boolean isLocalHost(String host) {
        if (host == null) {
            return false;
        }
        try {
            if (host != null) {
                if (host.equalsIgnoreCase("localhost")) {
                    return true;
                }
                if (NetworkUtils.numericToInetAddress(host).isLoopbackAddress()) {
                    return true;
                }
            }
        } catch (IllegalArgumentException iex) {
        }
        return false;
    }

    /**
     * Validate syntax of hostname, port and exclusion list entries
     * {@hide}
     */
    public static int validate(String hostname, String port, String exclList) {
        Matcher match = HOSTNAME_PATTERN.matcher(hostname);
        Matcher listMatch = EXCLLIST_PATTERN.matcher(exclList);

        if (!match.matches()) return PROXY_HOSTNAME_INVALID;

        if (!listMatch.matches()) return PROXY_EXCLLIST_INVALID;

        if (hostname.length() > 0 && port.length() == 0) return PROXY_PORT_EMPTY;

        if (port.length() > 0) {
            if (hostname.length() == 0) return PROXY_HOSTNAME_EMPTY;
            int portVal = -1;
            try {
                portVal = Integer.parseInt(port);
            } catch (NumberFormatException ex) {
                return PROXY_PORT_INVALID;
            }
            if (portVal <= 0 || portVal > 0xFFFF) return PROXY_PORT_INVALID;
        }
        return PROXY_VALID;
    }

    /** @hide */
    public static final void setHttpProxySystemProperty(ProxyInfo p) {
        String host = null;
        String port = null;
        String exclList = null;
        Uri pacFileUrl = Uri.EMPTY;
        if (p != null) {
            host = p.getHost();
            port = Integer.toString(p.getPort());
            exclList = p.getExclusionListAsString();
            pacFileUrl = p.getPacFileUrl();
        }
        setHttpProxySystemProperty(host, port, exclList, pacFileUrl);

        ///M: Support Telcel Requirement @{
        if("1".equals(SystemProperties.get("ro.mtk_pre_sim_wo_bal_support", "0"))) {
          Log.d(TAG, "setHttpRedirectNotifyHandler");
          java.net.Socket.setHttpRedirectNotifyHandler(new DefaultHttpNotifyHandler());
        }
        ///@}

        ///M: Add by MTK @{
        if (MobileManagerUtils.isSupported()) {
            Log.d(TAG, "setHttpRequestCheckHandler");
            AbstractHttpClient.setHttpRequestCheckHandler(new DefaultHttpRequestCheckHandler());
            Socket.setSocketMomCheckHandler(new DefaultSocketMomCheckHandler());
        }
        ///@}
    }

    /** @hide */
    public static final void setHttpProxySystemProperty(String host, String port, String exclList,
            Uri pacFileUrl) {
        if (exclList != null) exclList = exclList.replace(",", "|");
        if (false) Log.d(TAG, "setHttpProxySystemProperty :"+host+":"+port+" - "+exclList);
        if (host != null) {
            System.setProperty("http.proxyHost", host);
            System.setProperty("https.proxyHost", host);
        } else {
            System.clearProperty("http.proxyHost");
            System.clearProperty("https.proxyHost");
        }
        if (port != null) {
            System.setProperty("http.proxyPort", port);
            System.setProperty("https.proxyPort", port);
        } else {
            System.clearProperty("http.proxyPort");
            System.clearProperty("https.proxyPort");
        }
        if (exclList != null) {
            System.setProperty("http.nonProxyHosts", exclList);
            System.setProperty("https.nonProxyHosts", exclList);
        } else {
            System.clearProperty("http.nonProxyHosts");
            System.clearProperty("https.nonProxyHosts");
        }
        if (!Uri.EMPTY.equals(pacFileUrl)) {
            ProxySelector.setDefault(new PacProxySelector());
        } else {
            ProxySelector.setDefault(sDefaultProxySelector);
        }
    }

    ///M: Support MoM checking @{
    private static class DefaultHttpRequestCheckHandler implements HttpRequestCheckHandler {

        DefaultHttpRequestCheckHandler() {
            super();
        }

        public boolean checkMmsSendRequest() {
            Log.v(TAG, "checkCtaPermission");
            return MobileManagerUtils.checkPermission(SubPermissions.SEND_MMS,
                                                    Binder.getCallingUid());
        }

        public boolean checkEmailSendRequest() {
            Log.v(TAG, "checkCtaPermission for email");
            return MobileManagerUtils.checkPermission(SubPermissions.SEND_EMAIL,
                                                    Binder.getCallingUid());
        }

    }

    private static class DefaultSocketMomCheckHandler implements SocketCheckHandler {

        DefaultSocketMomCheckHandler() {
            super();
        }

        public boolean checkEmailSendRequest() {
            return MobileManagerUtils.checkPermission(SubPermissions.SEND_EMAIL,
                                                    Binder.getCallingUid());
        }
    }
    ///@}

	private static class DefaultHttpNotifyHandler implements HttpNotifyHandler {
		DefaultHttpNotifyHandler() {
			super();
		}
		
		public void notifyHttpRedirect(String location) {
			try{
				Log.v(TAG, "[NetworkHttpMonitor] call cm.monitorHttpRedirect");
				IConnectivityManager cm = IConnectivityManager.Stub.asInterface(
					ServiceManager.getService(Context.CONNECTIVITY_SERVICE));
	            cm.monitorHttpRedirect(location);
			} catch (Exception e) {
				Log.v(TAG, "[NetworkHttpMonitor] call cm.monitorHttpRedirect failed");
				e.printStackTrace();
			}
		}
	}

}
