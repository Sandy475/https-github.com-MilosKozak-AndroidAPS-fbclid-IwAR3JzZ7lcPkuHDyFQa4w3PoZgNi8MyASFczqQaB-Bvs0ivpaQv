package info.nightscout.androidaps.plugins.pump.omnipod.api;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.DhcpInfo;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;

import com.squareup.otto.Subscribe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.events.EventPreferenceChange;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.bus.RxBus;
import info.nightscout.androidaps.plugins.general.overview.events.EventDismissNotification;
import info.nightscout.androidaps.plugins.general.overview.events.EventNewNotification;
import info.nightscout.androidaps.plugins.general.overview.notifications.Notification;
import info.nightscout.androidaps.plugins.pump.omnipod.api.rest.OmnipyCallback;
import info.nightscout.androidaps.plugins.pump.omnipod.api.rest.OmnipyConstants;
import info.nightscout.androidaps.plugins.pump.omnipod.api.rest.OmnipyRequestType;
import info.nightscout.androidaps.plugins.pump.omnipod.api.rest.OmnipyRequest;
import info.nightscout.androidaps.plugins.pump.omnipod.api.rest.OmnipyResult;
import info.nightscout.androidaps.plugins.pump.omnipod.events.EventOmnipyApiResult;
import info.nightscout.androidaps.plugins.pump.omnipod.events.EventOmnipyConfigurationComplete;
import info.nightscout.androidaps.utils.SP;

import static java.net.NetworkInterface.getNetworkInterfaces;

public class OmnipyRestApi {

    private String REST_URL_PING = "/omnipy/ping";
    private String REST_URL_TOKEN = "/omnipy/token";
    private String REST_URL_NEW_POD = "/omnipy/newpod";
    private String REST_URL_GET_PDM_ADDRESS = "/omnipy/pdmspy";
    private String REST_URL_CHECK_PASSWORD = "/omnipy/pwcheck";
    private String REST_URL_SET_POD_PARAMETERS = "/omnipy/parameters";

    private String REST_URL_STATUS = "/pdm/status";
    private String REST_URL_PDM_BUSY = "/pdm/isbusy";
    private String REST_URL_ACK_ALERTS = "/pdm/ack";
    private String REST_URL_DEACTIVATE_POD = "/pdm/deactivate";
    private String REST_URL_BOLUS = "/pdm/bolus";
    private String REST_URL_CANCEL_BOLUS = "/pdm/cancelbolus";
    private String REST_URL_SET_TEMP_BASAL = "/pdm/settempbasal";
    private String REST_URL_CANCEL_TEMP_BASAL = "/pdm/canceltempbasal";
    private String REST_URL_SET_BASAL_SCHEDULE = "/pdm/setbasalschedule";

    private String REST_URL_ARCHIVE_POD = "/pdm/archive";
    private String REST_URL_PAIR_POD = "/pdm/pair";
    private String REST_URL_ACTIVATE_POD = "/pdm/activate";
    private String REST_URL_START_POD = "/pdm/start";

    private String REST_URL_RL_INFO = "/rl/info";

    private String _baseUrl;
    private OmnipyApiSecret _apiSecret;
    private RestApiConfigurationTask _configurationTask = null;
    private final Context _context;
    private boolean _configured;
    private boolean _configuring;
    private boolean _discovered;
    private boolean _connectable;
    private boolean _authenticated;
    private long _lastSuccessfulConnection = 0;

    private String _host;
    private final Logger _log;

    private final LinkedBlockingDeque<OmnipyRequest> _requestQueue;


    public OmnipyRestApi(Context context) {
        _context = context;
        _log =  LoggerFactory.getLogger(L.PUMP);
        _requestQueue = new LinkedBlockingDeque<OmnipyRequest>(10);
    }

    public boolean isConfigured() { return _configured; }
    public boolean isConfiguring() { return _configuring; }
    public boolean isDiscovered() {
        return _discovered;
    }
    public boolean isConnectable() {
        return _connectable;
    }
    public boolean isAuthenticated() {
        return _authenticated;
    }
    public String getHost() { return _host; }

    public long getLastSuccessfulConnection()
    {
        return _lastSuccessfulConnection;
    }



    public OmnipyRequest UpdateStatus(OmnipyCallback callback)
    {
        return UpdateStatus(0, callback);
    }

    public OmnipyRequest UpdateStatus(int requestType, OmnipyCallback callback)
    {
        return queue(new OmnipyRequest(OmnipyRequestType.Status, _baseUrl)
                .withAuthentication(_apiSecret)
                .withParameter(OmnipyConstants.OMNIPY_PARAM_STATUS_TYPE,
                        Integer.toString(requestType))
                .withCallback(callback));
    }

    public OmnipyRequest Ping(OmnipyCallback callback) {
        return new OmnipyRequest(OmnipyRequestType.Ping, _baseUrl)
                .withCallback(callback)
                .executeAsync();
    }

    public OmnipyRequest CheckAuthentication(OmnipyCallback callback) {
        return new OmnipyRequest(OmnipyRequestType.CheckPassword, _baseUrl)
                .withAuthentication(_apiSecret)
                .withCallback(callback)
                .executeAsync();
    }

    public OmnipyRequest GetAddressFromPdm(int timeout, OmnipyCallback callback) {
        return new OmnipyRequest(OmnipyRequestType.ReadPdmAddress, _baseUrl)
                .withAuthentication(_apiSecret)
                .withCallback(callback)
                .withParameter(OmnipyConstants.OMNIPY_PARAM_PDM_ADDRESS_TIMEOUT,
                        Integer.toString(timeout))
                .executeAsync();
    }

    public OmnipyRequest CreateNewPod(int lot, int tid, int address, OmnipyCallback callback) {
        return queue(new OmnipyRequest(OmnipyRequestType.NewPod, _baseUrl)
                .withAuthentication(_apiSecret)
                .withCallback(callback)
                .withParameter(OmnipyConstants.OMNIPY_PARAM_NEW_POD_LOT,
                        Integer.toString(lot))
                .withParameter(OmnipyConstants.OMNIPY_PARAM_NEW_POD_TID,
                        Integer.toString(tid))
                .withParameter(OmnipyConstants.OMNIPY_PARAM_NEW_POD_ADDRESS,
                        Integer.toString(address)));
    }

    public OmnipyRequest UpdatePodParameter(String parameter, String value, OmnipyCallback callback)
    {
        return queue(new OmnipyRequest(OmnipyRequestType.SetPodParameters, _baseUrl)
                .withAuthentication(_apiSecret)
                .withCallback(callback)
                .withParameter(parameter, value));
    }

    public OmnipyRequest GetRLInfo(OmnipyCallback callback) {
        return queue(new OmnipyRequest(OmnipyRequestType.RLInfo, _baseUrl)
                .withAuthentication(_apiSecret)
                .withCallback(callback));
    }

    public OmnipyRequest AcknowledgeAlerts(int alertMask, OmnipyCallback callback) {
        return queue(new OmnipyRequest(OmnipyRequestType.AckAlerts, _baseUrl)
                .withAuthentication(_apiSecret)
                .withCallback(callback)
                .withParameter(OmnipyConstants.OMNIPY_PARAM_ACK_ALERT_MASK,
                        Integer.toString(alertMask)));
    }

    public OmnipyRequest DeactivatePod(OmnipyCallback callback) {
        return queue(new OmnipyRequest(OmnipyRequestType.DeactivatePod, _baseUrl)
                .withAuthentication(_apiSecret)
                .withCallback(callback));
    }


    public OmnipyRequest ArchivePod(OmnipyCallback callback) {
        return queue(new OmnipyRequest(OmnipyRequestType.ArchivePod, _baseUrl)
                .withAuthentication(_apiSecret)
                .withCallback(callback));
    }

    public OmnipyRequest PairPod(int utc_offset, OmnipyCallback callback) {
        return queue(new OmnipyRequest(OmnipyRequestType.PairPod, _baseUrl)
                .withAuthentication(_apiSecret)
                .withParameter("utc", Integer.toString(utc_offset))
                .withCallback(callback));
    }

    public OmnipyRequest ActivatePod(OmnipyCallback callback) {
        return queue(new OmnipyRequest(OmnipyRequestType.ActivatePod, _baseUrl)
                .withAuthentication(_apiSecret)
                .withCallback(callback));
    }

    public OmnipyRequest StartPod(BigDecimal[] basalSchedule, OmnipyCallback callback) {
        OmnipyRequest request = new OmnipyRequest(OmnipyRequestType.StartPod, _baseUrl)
                .withAuthentication(_apiSecret)
                .withCallback(callback);

        for(int i=0; i<48; i++)
            request.withParameter("h" + Integer.toString(i), basalSchedule[i].toString());

        return queue(request);
    }


    public OmnipyRequest Bolus(BigDecimal bolusAmount, OmnipyCallback callback) {
        return queue(new OmnipyRequest(OmnipyRequestType.Bolus, _baseUrl)
                .withAuthentication(_apiSecret)
                .withCallback(callback)
                .withParameter(OmnipyConstants.OMNIPY_PARAM_BOLUS_AMOUNT,
                        bolusAmount.toString()));
    }

    public OmnipyRequest CancelBolus(OmnipyCallback callback) {
        return queue(new OmnipyRequest(OmnipyRequestType.CancelBolus, _baseUrl)
                .withAuthentication(_apiSecret)
                .withCallback(callback));
    }

    public OmnipyRequest Shutdown(OmnipyCallback callback) {
        return queue(new OmnipyRequest(OmnipyRequestType.Shutdown, _baseUrl)
                .withAuthentication(_apiSecret)
                .withCallback(callback));
    }

    public OmnipyRequest Restart(OmnipyCallback callback) {
        return queue(new OmnipyRequest(OmnipyRequestType.Restart, _baseUrl)
                .withAuthentication(_apiSecret)
                .withCallback(callback));
    }

    public OmnipyRequest SetTempBasal(BigDecimal basalRate, BigDecimal durationInHours,
                             OmnipyCallback callback)
    {
        return queue(new OmnipyRequest(OmnipyRequestType.TempBasal, _baseUrl)
                .withAuthentication(_apiSecret)
                .withCallback(callback)
                .withParameter(OmnipyConstants.OMNIPY_PARAM_TEMPBASAL_RATE,
                        basalRate.toString())
                .withParameter(OmnipyConstants.OMNIPY_PARAM_TEMPBASAL_HOURS,
                        durationInHours.toString()));
    }

    public OmnipyRequest CancelTempBasal(OmnipyCallback callback) {
        return queue(new OmnipyRequest(OmnipyRequestType.CancelTempBasal, _baseUrl)
                .withAuthentication(_apiSecret)
                .withCallback(callback));
    }

    public OmnipyRequest setBasalSchedule(BigDecimal[] basalSchedule, int utc_offset,
                                          OmnipyCallback callback) {
        OmnipyRequest request = new OmnipyRequest(OmnipyRequestType.SetBasalSchedule, _baseUrl)
                .withAuthentication(_apiSecret)
                .withParameter("utc", Integer.toString(utc_offset))
                .withCallback(callback);

        for(int i=0; i<48; i++)
            request.withParameter("h" + Integer.toString(i), basalSchedule[i].toString());

        return queue(request);
    }


    public OmnipyResult IsBusy() {
        return new OmnipyRequest(OmnipyRequestType.IsBusy, _baseUrl)
                .execute(10000);
    }

    public OmnipyRequest IsBusy(OmnipyCallback callback) {
        return new OmnipyRequest(OmnipyRequestType.IsBusy, _baseUrl)
                .withCallback(callback);
    }

    public void StopConfiguring() {
        if (!_configuring)
            return;

        if (_configurationTask != null)
            _configurationTask.cancel();

        _configurationTask = null;

        _baseUrl = null;
        _host = null;
        _configured = false;
        _configuring = false;
        _connectable = false;
        _authenticated = false;
        _discovered = false;
    }

    public void StartConfiguring()  {
        if (_configuring)
            return;

        if (_configurationTask != null)
            _configurationTask.cancel();

        _configured = false;
        _baseUrl = null;
        _host = null;
        _connectable = false;
        _authenticated = false;
        _discovered = false;

        _configuring = true;

        if (SP.getBoolean(R.string.key_omnipy_autodetect_host, true))
        {
            RxBus.INSTANCE.send(new EventDismissNotification(Notification.OMNIPY_CONNECTION_STATUS));
            Notification notification = new Notification(Notification.OMNIPY_CONNECTION_STATUS, MainApp.gs(R.string.Searching_for_omnipy_on_local_network), Notification.NORMAL, 1);    //"Searching for omnipy on local network.."
            RxBus.INSTANCE.send(new EventNewNotification(notification));

        }
        else
        {
            String omnipyHost = SP.getString(R.string.key_omnipy_host, null);
            if (omnipyHost != null && omnipyHost.length() == 0) {
                RxBus.INSTANCE.send(new EventDismissNotification(Notification.OMNIPY_CONNECTION_STATUS));
                Notification notification = new Notification(Notification.OMNIPY_CONNECTION_STATUS, MainApp.gs(R.string.Trying_to_connect_to_omnipy_at_address) + omnipyHost, Notification.INFO, 1);    //"Trying to connect to omnipy at address:"
                RxBus.INSTANCE.send(new EventNewNotification(notification));
            }
        }

        _configurationTask = new RestApiConfigurationTask(_context, this);
        _configurationTask.execute();
    }

    public void setConfigurationComplete(final EventOmnipyConfigurationComplete confResult) {
        _host = confResult.hostName;
        if (_host != null)
            _baseUrl = "http://" + _host + ":4444";
        else
            _baseUrl = null;

        _apiSecret = confResult.apiSecret;
        _authenticated = confResult.isAuthenticated;
        _discovered = confResult.isDiscovered;
        _connectable = confResult.isConnectable;

        _configurationTask = null;
        _configuring = false;
        _configured = true;

        RxBus.INSTANCE.send(confResult);
    }

    @Subscribe
    public void onRequestComplete(final EventOmnipyApiResult result) {
        if (result.getResult().success)
            _lastSuccessfulConnection = System.currentTimeMillis();
        runNext();
    }

    private synchronized OmnipyRequest queue(OmnipyRequest request)
    {
        Iterator<OmnipyRequest> iterator = _requestQueue.iterator();
        ArrayList<OmnipyRequest> requeuedList = new ArrayList<>();
        while (iterator.hasNext())
        {
            OmnipyRequest queuedRequest = iterator.next();
            OmnipyRequestType queuedType = queuedRequest.getRequestType();

            switch (request.getRequestType())
            {
                case Ping:
                    if (queuedRequest.getRequestType() != OmnipyRequestType.Ping) {
                        iterator.remove();
                        queuedRequest.cancel();
                    }
                    break;
                case Status:
                    if (queuedRequest.getRequestType() != OmnipyRequestType.Status) {
                        iterator.remove();
                        queuedRequest.cancel();
                    }
                case CancelBolus:
                    iterator.remove();
                    if (queuedRequest.getRequestType() != OmnipyRequestType.Bolus)
                        requeuedList.add(queuedRequest);
                    else
                        queuedRequest.cancel();
                    break;
                case TempBasal:
                    if (queuedRequest.getRequestType() == OmnipyRequestType.TempBasal)
                        queuedRequest.cancel();
                    break;
                case CancelTempBasal:
                    if (queuedRequest.getRequestType() == OmnipyRequestType.TempBasal)
                        queuedRequest.cancel();
                default:
                    break;
            }
        }

        _requestQueue.addAll(requeuedList);
        _requestQueue.add(request);

        runNext();
        return request;
    }

    private synchronized void runNext()
    {
        if (_requestQueue.size() > 0) {
            OmnipyRequest request = _requestQueue.remove();
            request.executeAsync();
        }
    }

    public void onAvailable(Network network) {

    }

    public void onLosing(Network network, int maxMsToLive) {
    }

    public void onLost(Network network) {

    }

    public void onUnavailable() {

    }

    public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {

    }

    public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {

    }

}

class RestApiConfigurationTask extends AsyncTask<Void, Void, String> {

    private final Context _context;
    private final Logger _log;
    private boolean _canceled = false;
    private OmnipyRestApi _restApi;

    public RestApiConfigurationTask(Context context, OmnipyRestApi restApi)
    {
        _context = context;
        _restApi = restApi;
        _log =  LoggerFactory.getLogger(L.PUMP);
    }

    public void cancel()
    {
        _canceled = true;
    }

    @Override
    protected String doInBackground(Void... voids) {
        String omnipyHost = null;

        boolean autodetect = SP.getBoolean(R.string.key_omnipy_autodetect_host, true);
        if (autodetect) {
            while (true)
            {
                List<NetworkInterface> interfaces = null;
                try {
                    interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
                    for (NetworkInterface intf : interfaces) {
                        List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                        for (InetAddress addr : addrs) {
                            if (!addr.isLoopbackAddress()) {
                                if (_canceled)
                                    return null;
                                byte[] addrBytes = addr.getAddress();
                                if (addrBytes.length == 4) {
                                    addrBytes[3] = (byte) 0xFF;
                                    InetAddress broadcastAddress = InetAddress.getByAddress(addrBytes);
                                    omnipyHost = discover(broadcastAddress);
                                    if (omnipyHost != null) {
                                        break;
                                    }
                                }
                            }
                        }
                        if (omnipyHost != null)
                            break;
                    }
                } catch (SocketException | UnknownHostException e) {
                e.printStackTrace();
            }

            if (omnipyHost != null)
                break;

            try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        else
        {
            omnipyHost = SP.getString(R.string.key_omnipy_host, null);
            if (omnipyHost != null && omnipyHost.length() == 0) {
                omnipyHost = null;
            }
        }

        if (_canceled)
            return null;
        else
            return omnipyHost;

    }

    @Override
    protected void onPostExecute(String omnipyHost) {
        boolean discovered = false;
        boolean connectable = false;
        boolean authenticated = false;
        OmnipyApiSecret apiSecret = null;

        if (omnipyHost != null) {
            discovered = SP.getBoolean(R.string.key_omnipy_autodetect_host, true);
            String baseUrl = "http://" + omnipyHost + ":4444";

            String secret = SP.getString(R.string.key_omnipy_password, "");
            if (secret.length() > 0) {
                apiSecret = OmnipyApiSecret.fromPassphrase(secret);
                OmnipyResult result = new OmnipyRequest(OmnipyRequestType.CheckPassword, baseUrl)
                        .withAuthentication(apiSecret)
                        .execute(10000);

                if (result.success) {
                    connectable = true;
                    authenticated = true;
                } else {
                    if (result.exception == null)
                        connectable = true;
                }
            } else {
                OmnipyResult result = new OmnipyRequest(OmnipyRequestType.Ping, baseUrl)
                        .execute(10000);

                if (result.success)
                    connectable = true;
            }
        }

        if (!_canceled) {
            _restApi.setConfigurationComplete(new EventOmnipyConfigurationComplete(omnipyHost, apiSecret,
                            discovered, connectable, authenticated));
        }
    }

    private String discover(InetAddress broadcastAddress)
    {
        DatagramSocket listenSocket = null;
        DatagramSocket sendSocket = null;
        try
        {
            byte[] receive = new byte[1024];
            listenSocket = new DatagramSocket(6665);
            DatagramPacket listenPacket = new DatagramPacket(receive, receive.length);
            listenSocket.setSoTimeout(5000);
            listenSocket.setBroadcast(true);

            sendSocket = new DatagramSocket();
            sendSocket.setSoTimeout(3000);
            sendSocket.setBroadcast(true);
            byte[] data = "Oh dear.".getBytes(StandardCharsets.US_ASCII);

            DatagramPacket sendPacket = new DatagramPacket(data, data.length, broadcastAddress,
                    6664);

            _log.debug("Sending broadcast message to " + broadcastAddress.toString());
            sendSocket.send(sendPacket);
            _log.debug("Waiting for omnipy to respond");
            listenSocket.receive(listenPacket);
            _log.debug("Received a response of length %d from %s", receive.length,
                    listenPacket.getAddress().toString());
            byte[] received = listenPacket.getData();
            byte[] compare = "wut".getBytes(StandardCharsets.US_ASCII);
            if (received.length >= 3 && received[0] == compare[0] &&
                    received[1] == compare[1] && received[2] == compare[2])
            {
                _log.debug("Response match for omnipy");
                return listenPacket.getAddress().toString().replace("/", "");
            }
            else
            {
                _log.debug("Response is not what we expected");
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally
        {
            if (listenSocket != null)
                listenSocket.close();
            if (sendSocket != null)
                sendSocket.close();
        }
        return null;

    }
}
