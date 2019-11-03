package info.nightscout.androidaps.plugins.pump.omnipod.api.rest;

import android.net.Uri;
import android.os.AsyncTask;
import android.util.Pair;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import info.nightscout.androidaps.plugins.bus.RxBus;

//import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.plugins.pump.omnipod.api.OmnipyApiSecret;
import info.nightscout.androidaps.plugins.pump.omnipod.api.OmnipyApiToken;
import info.nightscout.androidaps.plugins.pump.omnipod.events.EventOmnipyApiResult;
import info.nightscout.androidaps.plugins.pump.omnipod.exceptions.OmnipyConnectionException;
import info.nightscout.androidaps.plugins.pump.omnipod.util.Base64;

public class OmnipyRequest {

    private final String _baseUrl;
    private final OmnipyRequestType _requestType;
    private OmnipyApiSecret _apiSecret;
    private final ArrayList<Pair<String,String>> _parameters;
    private OmnipyCallback _callback;
    private final OmnipyRequestTask _task;

    private int _versionRequiredMajor = 0;
    private int _versionRequiredMinor = 0;

    public long created;
    public long requested;
    public long responseReceived;

    public OmnipyRequest(OmnipyRequestType requestType, String baseUrl)
    {
        _baseUrl = baseUrl;
        _requestType = requestType;
        _parameters = new ArrayList<>();
        _task = new OmnipyRequestTask(this);
        created = System.currentTimeMillis();
    }

    public OmnipyRequest withAuthentication(OmnipyApiSecret apiSecret)
    {
        _apiSecret = apiSecret;
        return this;
    }

    public OmnipyRequest withParameter(String key, String value)
    {
        _parameters.add(new Pair<>(key, value));
        return this;
    }

    public OmnipyRequest withCallback(OmnipyCallback callback)
    {
        _callback = callback;
        return this;
    }

    public OmnipyRequest minimumApiVersion(int major, int minor)
    {
        _versionRequiredMajor = major;
        _versionRequiredMinor = minor;
        return this;
    }

    public OmnipyResult execute()
    {
        return execute(0);
    }

    public OmnipyResult execute(long timeout)
    {
        OmnipyResult result = new OmnipyResult();
        try {
            if (timeout == 0)
                result = _task.execute().get();
            else
                result = _task.execute().get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            e.printStackTrace();
            result.exception = e;
            result.success = false;
        }
        return result;
    }

    public OmnipyResult waitForResult()
    {
        try {
            return _task.get();
        } catch (InterruptedException | ExecutionException e) {
            OmnipyResult res = new OmnipyResult();
            res.success = false;
            res.exception = e;
            res.originalRequest = this;
            return res;
        }
    }

    public OmnipyRequestType getRequestType() { return _requestType; }

    public OmnipyRequest executeAsync()
    {
        _task.execute();
        return this;
    }

    public void cancel()
    {
        OmnipyResult result = new OmnipyResult();
        result.success = false;
        result.canceled = true;
        result.originalRequest = this;
        if (_callback != null)
        {
            try {
                _callback.onResultReceived(result);
            }
            catch(Exception e)
            {
                e.printStackTrace();
            }
        }
        RxBus.INSTANCE.send(new EventOmnipyApiResult(result));
    }

    public URL getUrl() throws MalformedURLException {
        String urlString = _baseUrl + OmnipyConstants.getPath(_requestType);
        Uri.Builder ub = Uri.parse(urlString).buildUpon();

        for (Pair<String,String> parameter : _parameters) {
            ub.appendQueryParameter(parameter.first, parameter.second);
        }

        return new URL(ub.toString());
    }

    public URL getTokenUrl() throws MalformedURLException {
        String urlString = _baseUrl + OmnipyConstants.getPath(OmnipyRequestType.Token);
        Uri.Builder ub = Uri.parse(urlString).buildUpon();

        return new URL(ub.toString());
    }

    public OmnipyApiSecret getSecret()
    {
        return _apiSecret;
    }

    public OmnipyCallback getCallback()
    {
        return _callback;
    }
}

class OmnipyRequestTask extends AsyncTask<String, Void, OmnipyResult> {

    private final OmnipyRequest _request;
    public OmnipyRequestTask(OmnipyRequest request)
    {
        _request = request;
    }

    @Override
    protected OmnipyResult doInBackground(String... strings) {
        OmnipyResult result = new OmnipyResult();
        result.originalRequest = _request;
        OmnipyApiSecret apiSecret = _request.getSecret();
        OmnipyApiToken apiToken;

        if (apiSecret != null) {
            try {
                URL url = _request.getTokenUrl();
                HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();

                int responseCode = urlConnection.getResponseCode();

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    String response = readStream(urlConnection.getInputStream());
                    result = OmnipyResult.fromJson(response, _request);
                } else
                {
                    result.exception = new OmnipyConnectionException();
                    result.success = false;
                    return result;
                }

                String tokenStr = result.response.get("token").getAsString();
                apiToken = new OmnipyApiToken(Base64.decode(tokenStr), apiSecret);

                String authToken = Base64.encode(apiToken.getAuthenticationToken());
                String iv = Base64.encode(apiToken.getIV());

                _request.withParameter(OmnipyConstants.OMNIPY_PARAM_AUTH, authToken);
                _request.withParameter(OmnipyConstants.OMNIPY_PARAM_IV, iv);

            } catch (Exception e) {
                result.exception = e;
                result.success = false;
                return result;
            }
        }

        result = new OmnipyResult();
        result.originalRequest = _request;
        try {
            _request.requested = System.currentTimeMillis();
            _request.withParameter("req_t", Long.toString(_request.requested));
            URL url = _request.getUrl();
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();

            int responseCode = urlConnection.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK) {
                String response = readStream(urlConnection.getInputStream());
                result = OmnipyResult.fromJson(response, _request);
            }
            else
            {
                result.exception = new OmnipyConnectionException();
                result.success = false;
            }
        } catch (Exception e) {
            result.exception = e;
            result.success = false;
        }
        _request.responseReceived = System.currentTimeMillis();
        return result;
    }

    @Override
    protected void onPostExecute(OmnipyResult result) {
        OmnipyCallback cb = _request.getCallback();
        if (cb != null)
        {
            try {
                cb.onResultReceived(result);
            }
            catch(Exception e)
            {
                e.printStackTrace();
            }
        }
        RxBus.INSTANCE.send(new EventOmnipyApiResult(result));
    }

    private String readStream(InputStream in) throws IOException {
        BufferedReader reader = null;
        StringBuffer response = new StringBuffer();
        try
        {
            reader = new BufferedReader(new InputStreamReader(in));
            String line;

            while ((line = reader.readLine()) != null)
                response.append(line);

        } finally {
            if (reader != null)
                reader.close();
        }
        return response.toString();
    }
}