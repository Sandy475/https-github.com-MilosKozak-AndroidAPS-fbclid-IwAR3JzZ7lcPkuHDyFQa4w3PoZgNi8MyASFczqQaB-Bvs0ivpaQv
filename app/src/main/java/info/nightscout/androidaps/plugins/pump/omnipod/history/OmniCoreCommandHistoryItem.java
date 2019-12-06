package info.nightscout.androidaps.plugins.pump.omnipod.history;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.plugins.bus.RxBus;
import info.nightscout.androidaps.plugins.general.nsclient.NSUpload;
import info.nightscout.androidaps.plugins.general.overview.events.EventDismissNotification;
import info.nightscout.androidaps.plugins.general.overview.events.EventNewNotification;
import info.nightscout.androidaps.plugins.general.overview.notifications.Notification;
import info.nightscout.androidaps.plugins.pump.omnipod.api.rest.OmniCoreRequest;
import info.nightscout.androidaps.plugins.pump.omnipod.api.rest.OmniCoreResult;
import info.nightscout.androidaps.utils.DateUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.utils.SP;


public class OmniCoreCommandHistoryItem {

    private final Logger _log;

    private OmniCoreResult _result;
    private OmniCoreRequest _request;
    private String _status;
    private Long _lastUpdate;



    public OmniCoreCommandHistoryItem(OmniCoreRequest request, OmniCoreResult result) {
        _log =  LoggerFactory.getLogger(L.PUMP);
        this._request = request;
        this._result = result;

        processHistoryItem();

    }

    public String getStatus() {
        return this._status;
    }

    public OmniCoreRequest getRequest() {
        return this._request;
    }

    public OmniCoreResult getResult() {
        return this._result;
    }

    public void setResult(OmniCoreResult result) {

        this._result = result;
        processHistoryItem();
    }

    public void setFailed() {
        this._status = "Failure";
        this._lastUpdate = DateUtil.now();
        processHistoryItem();
    }

    private void processHistoryItem() {
        if (this._result == null) {
            if (this._status != "Failure") {
                this._status = "Pending";
            }
        }
        else {
            if (this._result.Success) {
                this._status = "Success";
                RxBus.INSTANCE.send(new EventDismissNotification(Notification.OMNIPY_COMMAND_STATUS));

            }
            else {
                this._status = "Failure";

                /*    public static final int URGENT = 0;
    public static final int NORMAL = 1;
    public static final int LOW = 2;
    public static final int INFO = 3;
    public static final int ANNOUNCEMENT = 4;*/
                int alertLevel = SP.getInt(R.string.key_omnicore_failure_alerttype,-1);
                String alertText = String.format(MainApp.gs(R.string.omnipod_command_state_lastcommand_failed), this._request.getRequestType());
                if (alertLevel >=0) {
                    Notification notification = new Notification(Notification.OMNIPY_COMMAND_STATUS, alertText, alertLevel);
                    if (SP.getBoolean(R.string.key_omnicore_failure_audible,false)) {
                        notification.soundId = R.raw.alarm;
                    }
                    RxBus.INSTANCE.send(new EventNewNotification(notification));

                }
                //Log Failure to NS

                if (SP.getBoolean(R.string.key_omnicore_log_failures, false)) {
                    NSUpload.uploadError(alertText);
                }
            }
        }

        this._lastUpdate = DateUtil.now();
    }

    public long getStartTime() {
        return this._request.created;
    }


    public long getEndTime() {
       return this._lastUpdate;
    }

    public Boolean isSameRequest(OmniCoreRequest request) {
        return this.getStartTime() == request.created;
    }

    public long getRunTime() {
        return getEndTime() - getStartTime();
    }
}
