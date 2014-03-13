/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.email.activity.setup;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import com.android.email.R;
import com.android.email.activity.UiUtilities;
import com.android.email.service.EmailServiceUtils;
import com.android.email.service.EmailServiceUtils.EmailServiceInfo;
import com.android.email.view.CertificateSelector;
import com.android.email.view.CertificateSelector.HostCallback;
import com.android.emailcommon.Device;
import com.android.emailcommon.VendorPolicyLoader.OAuthProvider;
import com.android.emailcommon.provider.Credential;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.utility.CertificateRequestor;
import com.android.mail.utils.LogUtils;

import java.io.IOException;
import java.util.List;

public class AccountSetupCredentialsFragment extends AccountSetupFragment
        implements OnClickListener, HostCallback {

    private static final int CERTIFICATE_REQUEST = 1000;

    private static final String EXTRA_EMAIL = "email";
    private static final String EXTRA_PROTOCOL = "protocol";
    private static final String EXTRA_PASSWORD_FAILED = "password_failed";

    public static final String EXTRA_PASSWORD = "password";
    public static final String EXTRA_OAUTH_PROVIDER = "provider";
    public static final String EXTRA_OAUTH_ACCESS_TOKEN = "accessToken";
    public static final String EXTRA_OAUTH_REFRESH_TOKEN = "refreshToken";
    public static final String EXTRA_OAUTH_EXPIRES_IN_SECONDS = "expiresInSeconds";

    private View mOAuthGroup;
    private View mOAuthButton;
    private EditText mImapPasswordText;
    private EditText mRegularPasswordText;
    private TextWatcher mValidationTextWatcher;
    private TextView mPasswordWarningLabel;
    private TextView mEmailConfirmationLabel;
    private TextView mEmailConfirmation;
    private TextView.OnEditorActionListener mEditorActionListener;
    private String mEmailAddress;
    private boolean mOfferOAuth;
    private boolean mOfferCerts;
    private String mProviderId;
    private Context mAppContext;
    private Bundle mResults;
    private CertificateSelector mClientCertificateSelector;
    private View mDeviceIdSection;
    private TextView mDeviceId;

    public interface Callback extends AccountSetupFragment.Callback {
        void onCredentialsComplete(Bundle results);
    }

    /**
     * Create a new instance of this fragment with the appropriate email and protocol
     * @param email login address for OAuth purposes
     * @param protocol protocol of the service we're gathering credentials for
     * @return new fragment instance
     */
    public static AccountSetupCredentialsFragment newInstance(final String email,
            final String protocol, final boolean passwordFailed) {
        final AccountSetupCredentialsFragment f = new AccountSetupCredentialsFragment();
        final Bundle b = new Bundle(2);
        b.putString(EXTRA_EMAIL, email);
        b.putString(EXTRA_PROTOCOL, protocol);
        b.putBoolean(EXTRA_PASSWORD_FAILED, passwordFailed);
        f.setArguments(b);
        return f;
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
            final Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.account_setup_credentials_fragment, container,
                false);

        mImapPasswordText = UiUtilities.getView(view, R.id.imap_password);
        mRegularPasswordText = UiUtilities.getView(view, R.id.regular_password);
        mOAuthGroup = UiUtilities.getView(view, R.id.oauth_group);
        mOAuthButton = UiUtilities.getView(view, R.id.sign_in_with_google);
        mOAuthButton.setOnClickListener(this);
        mClientCertificateSelector = UiUtilities.getView(view, R.id.client_certificate_selector);
        mDeviceIdSection = UiUtilities.getView(view, R.id.device_id_section);
        mDeviceId = UiUtilities.getView(view, R.id.device_id);
        mClientCertificateSelector.setHostCallback(this);
        mPasswordWarningLabel  = UiUtilities.getView(view, R.id.wrong_password_warning_label);
        mEmailConfirmationLabel  = UiUtilities.getView(view, R.id.email_confirmation_label);
        mEmailConfirmation  = UiUtilities.getView(view, R.id.email_confirmation);

        mEditorActionListener = new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    final Callback callback = (Callback) getActivity();
                    if (callback != null) {
                        final Bundle results = new Bundle(1);
                        results.putString(EXTRA_PASSWORD, getPassword());
                        mResults = results;
                        callback.onCredentialsComplete(results);
                    }
                    return true;
                } else {
                    return false;
                }
            }
        };
        mImapPasswordText.setOnEditorActionListener(mEditorActionListener);
        mRegularPasswordText.setOnEditorActionListener(mEditorActionListener);

        // After any text edits, call validateFields() which enables or disables the Next button
        mValidationTextWatcher = new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                validatePassword();
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
        };
        mImapPasswordText.addTextChangedListener(mValidationTextWatcher);
        mRegularPasswordText.addTextChangedListener(mValidationTextWatcher);

        return view;
    }

    @Override
    public void onActivityCreated(final Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        mAppContext = getActivity().getApplicationContext();
        mEmailAddress = getArguments().getString(EXTRA_EMAIL);
        final String protocol = getArguments().getString(EXTRA_PROTOCOL);
        // TODO: for now, we might not know what protocol we're using, so just default to
        // offering oauth
        mOfferOAuth = true;
        mOfferCerts = true;
        if (protocol != null) {
            final EmailServiceInfo info = EmailServiceUtils.getServiceInfo(mAppContext, protocol);
            if (info != null) {
                mOfferOAuth = info.offerOAuth;
                mOfferCerts = info.offerCerts;
            }
        }
        mOAuthGroup.setVisibility(mOfferOAuth ? View.VISIBLE : View.GONE);
        mRegularPasswordText.setVisibility(mOfferOAuth ? View.GONE : View.VISIBLE);

        if (mOfferCerts) {
            // TODO: Here we always offer certificates for any protocol that allows them (i.e.
            // Exchange). But they will really only be available if we are using SSL security.
            // Trouble is, first time through here, we haven't offered the user the choice of
            // which security type to use.
            mClientCertificateSelector.setVisibility(mOfferCerts ? View.VISIBLE : View.GONE);
            mDeviceIdSection.setVisibility(mOfferCerts ? View.VISIBLE : View.GONE);
            String deviceId = "";
            try {
                deviceId = Device.getDeviceId(getActivity());
            } catch (IOException e) {
                // Not required
            }
            mDeviceId.setText(deviceId);
        }
        final boolean passwordFailed = getArguments().getBoolean(EXTRA_PASSWORD_FAILED, false);
        setPasswordFailed(passwordFailed);
        validatePassword();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mImapPasswordText != null) {
            mImapPasswordText.removeTextChangedListener(mValidationTextWatcher);
            mImapPasswordText.setOnEditorActionListener(null);
            mImapPasswordText = null;
        }
        if (mRegularPasswordText != null) {
            mRegularPasswordText.removeTextChangedListener(mValidationTextWatcher);
            mRegularPasswordText.setOnEditorActionListener(null);
            mRegularPasswordText = null;
        }
    }

    public void setPasswordFailed(final boolean failed) {
        if (failed) {
            mPasswordWarningLabel.setVisibility(View.VISIBLE);
            mEmailConfirmationLabel.setVisibility(View.VISIBLE);
            mEmailConfirmation.setVisibility(View.VISIBLE);
            mEmailConfirmation.setText(mEmailAddress);
        } else {
            mPasswordWarningLabel.setVisibility(View.GONE);
            mEmailConfirmationLabel.setVisibility(View.GONE);
            mEmailConfirmation.setVisibility(View.GONE);
        }
    }

    public void validatePassword() {
        final Callback callback = (Callback) getActivity();
        if (callback != null) {
            callback.setNextButtonEnabled(!TextUtils.isEmpty(getPassword()));
        }
        // Warn (but don't prevent) if password has leading/trailing spaces
        AccountSettingsUtils.checkPasswordSpaces(mAppContext, mImapPasswordText);
        AccountSettingsUtils.checkPasswordSpaces(mAppContext, mRegularPasswordText);
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        if (requestCode == CERTIFICATE_REQUEST) {
            if (resultCode == Activity.RESULT_OK) {
                final String certAlias = data.getStringExtra(CertificateRequestor.RESULT_ALIAS);
                if (certAlias != null) {
                    mClientCertificateSelector.setCertificate(certAlias);
                }
            } else {
                LogUtils.e(LogUtils.TAG, "Unknown result from certificate request %d",
                        resultCode);
            }
        } else if (requestCode == OAuthAuthenticationActivity.REQUEST_OAUTH) {
            if (resultCode == OAuthAuthenticationActivity.RESULT_OAUTH_SUCCESS) {
                final String accessToken = data.getStringExtra(
                        OAuthAuthenticationActivity.EXTRA_OAUTH_ACCESS_TOKEN);
                final String refreshToken = data.getStringExtra(
                        OAuthAuthenticationActivity.EXTRA_OAUTH_REFRESH_TOKEN);
                final int expiresInSeconds = data.getIntExtra(
                        OAuthAuthenticationActivity.EXTRA_OAUTH_EXPIRES_IN, 0);
                final Bundle results = new Bundle(4);
                results.putString(EXTRA_OAUTH_PROVIDER, mProviderId);
                results.putString(EXTRA_OAUTH_ACCESS_TOKEN, accessToken);
                results.putString(EXTRA_OAUTH_REFRESH_TOKEN, refreshToken);
                results.putInt(EXTRA_OAUTH_EXPIRES_IN_SECONDS, expiresInSeconds);
                mResults = results;
                final Callback callback = (Callback) getActivity();
                callback.onCredentialsComplete(results);
            } else if (resultCode == OAuthAuthenticationActivity.RESULT_OAUTH_FAILURE
                    || resultCode == OAuthAuthenticationActivity.RESULT_OAUTH_USER_CANCELED) {
                LogUtils.i(LogUtils.TAG, "Result from oauth %d", resultCode);
            } else {
                LogUtils.wtf(LogUtils.TAG, "Unknown result code from OAUTH: %d", resultCode);
            }
        } else {
            LogUtils.e(LogUtils.TAG, "Unknown request code for onActivityResult in"
                    + " AccountSetupBasics: %d", requestCode);
        }
    }

    @Override
    public void onClick(final View view) {
        if (view == mOAuthButton) {
            List<OAuthProvider> oauthProviders = AccountSettingsUtils.getAllOAuthProviders(
                    mAppContext);
            // TODO currently the only oauth provider we support is google.
            // If we ever have more than 1 oauth provider, then we need to implement some sort
            // of picker UI. For now, just always take the first oauth provider.
            if (oauthProviders.size() > 0) {
                mProviderId = oauthProviders.get(0).id;
                final Intent i = new Intent(getActivity(), OAuthAuthenticationActivity.class);
                i.putExtra(OAuthAuthenticationActivity.EXTRA_EMAIL_ADDRESS, mEmailAddress);
                i.putExtra(OAuthAuthenticationActivity.EXTRA_PROVIDER, mProviderId);
                startActivityForResult(i, OAuthAuthenticationActivity.REQUEST_OAUTH);
            }
        }
    }

    public String getPassword() {
        if (mOfferOAuth) {
            return mImapPasswordText.getText().toString();
        } else {
            return mRegularPasswordText.getText().toString();
        }
    }

    public Bundle getCredentialResults() {
        if (mResults != null) {
            return mResults;
        }

        final Bundle results = new Bundle(1);
        results.putString(EXTRA_PASSWORD, getPassword());
        return results;
    }

    public static void populateHostAuthWithResults(final Context context, final HostAuth hostAuth,
            final Bundle results) {
        if (results == null) {
            return;
        }
        final String password = results.getString(AccountSetupCredentialsFragment.EXTRA_PASSWORD);
        if (!TextUtils.isEmpty(password)) {
            hostAuth.mPassword = password;
            hostAuth.removeCredential();
        } else {
            Credential cred = hostAuth.getOrCreateCredential(context);
            cred.mProviderId = results.getString(
                    AccountSetupCredentialsFragment.EXTRA_OAUTH_PROVIDER);
            cred.mAccessToken = results.getString(
                    AccountSetupCredentialsFragment.EXTRA_OAUTH_ACCESS_TOKEN);
            cred.mRefreshToken = results.getString(
                    AccountSetupCredentialsFragment.EXTRA_OAUTH_REFRESH_TOKEN);
            cred.mExpiration = System.currentTimeMillis()
                    + results.getInt(
                    AccountSetupCredentialsFragment.EXTRA_OAUTH_EXPIRES_IN_SECONDS, 0)
                    * DateUtils.SECOND_IN_MILLIS;
            hostAuth.mPassword = null;
        }
    }

    public String getClientCertificate() {
        return mClientCertificateSelector.getCertificate();
    }

    @Override
    public void onCertificateRequested() {
        final Intent intent = new Intent(CertificateRequestor.ACTION_REQUEST_CERT);
        intent.setData(Uri.parse("eas://com.android.emailcommon/certrequest"));
        startActivityForResult(intent, CERTIFICATE_REQUEST);
    }
}