package info.nightscout.androidaps.plugins.pump.omnipod;

import android.content.Context;
import android.os.SystemClock;

//import com.squareup.otto.Subscribe;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import info.nightscout.androidaps.BuildConfig;
import info.nightscout.androidaps.Config;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.DetailedBolusInfo;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.data.PumpEnactResult;
import info.nightscout.androidaps.db.ExtendedBolus;
import info.nightscout.androidaps.db.Source;
import info.nightscout.androidaps.db.TemporaryBasal;
import info.nightscout.androidaps.events.EventNetworkChange;
import info.nightscout.androidaps.interfaces.Constraint;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PluginDescription;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.interfaces.PumpDescription;
import info.nightscout.androidaps.interfaces.PumpInterface;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.bus.RxBus;
import info.nightscout.androidaps.plugins.aps.loop.LoopPlugin;
import info.nightscout.androidaps.plugins.common.ManufacturerType;
import info.nightscout.androidaps.plugins.general.actions.defs.CustomAction;
import info.nightscout.androidaps.plugins.general.actions.defs.CustomActionType;
import info.nightscout.androidaps.plugins.general.overview.events.EventDismissNotification;
import info.nightscout.androidaps.plugins.general.overview.events.EventNewNotification;
import info.nightscout.androidaps.plugins.general.overview.events.EventOverviewBolusProgress;
import info.nightscout.androidaps.plugins.general.overview.notifications.Notification;
import info.nightscout.androidaps.plugins.pump.common.defs.PumpType;
import info.nightscout.androidaps.plugins.pump.omnipod.api.rest.OmnipyResult;
import info.nightscout.androidaps.plugins.pump.omnipod.events.EventOmnipodUpdateGui;
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.InstanceId;
import info.nightscout.androidaps.utils.SP;


import org.json.JSONException;

import java.math.BigDecimal;
import java.util.List;


public class OmnipodPlugin extends PluginBase implements PumpInterface {
    private final Logger log = LoggerFactory.getLogger(L.PUMP);


    private static OmnipodPlugin instance = null;
    private final PumpDescription pumpDescription = new PumpDescription();
    private final OmnipodPdm _pdm;
    private DetailedBolusInfo _runningBolusInfo;

    public static OmnipodPlugin getPlugin() {
        if (instance == null)
            instance = new OmnipodPlugin();
        return instance;
    }
    public OmnipodPlugin() {
        super(new PluginDescription()
                .mainType(PluginType.PUMP)
                .fragmentClass(OmnipodFragment.class.getName())
                .pluginName(R.string.omnipod)
                .shortName(R.string.omnipod_shortname)
                .preferencesId(R.xml.pref_omnipod)
                .neverVisible(Config.NSCLIENT)
                .description(R.string.omnipod_description)
        );

        // explicitly disable aaps interfering with bluetooth connections
        // as it assumes all pumps connect via bluetooth
        SP.putBoolean(R.string.key_btwatchdog, false);

        pumpDescription.setPumpDescription(PumpType.Omnipy_Omnipod);
        log.debug("omnipod plugin initialized");
        Context context = MainApp.instance().getApplicationContext();
        _pdm = new OmnipodPdm(context);
    }

    @Override
    protected void onStart() {
        super.onStart();
        MainApp.bus().register(this);
        log.debug("onstart");
        _pdm.OnStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
        _pdm.OnStop();
        MainApp.bus().unregister(this);
    }

    @Subscribe
    public void onStatusEvent(final EventNetworkChange enc) {
        RxBus.INSTANCE.send(new EventOmnipodUpdateGui());
    }

    public OmnipodPdm getPdm()
    {
        return _pdm;
    }

    @Override
    public boolean isBotheredByConstantConnectionRequests() { return true; }

    @Override
    public boolean isInitialized() {
        return _pdm.IsInitialized();
    }

    @Override
    public boolean isSuspended() {
        return _pdm.IsSuspended();
    }

    @Override
    public boolean isBusy() { return _pdm.IsBusy(); }

    @Override
    public boolean isConnected() { return _pdm.IsConnected(); }

    @Override
    public boolean isConnecting() { return _pdm.IsConnecting(); }

    @Override
    public boolean isHandshakeInProgress() { return _pdm.IsHandshakeInProgress();  }

    @Override
    public void finishHandshaking() { _pdm.FinishHandshaking(); }

    @Override
    public void connect(String reason) {
        _pdm.Connect();
        RxBus.INSTANCE.send(new EventOmnipodUpdateGui());
    }

    @Override
    public void disconnect(String reason) {
        _pdm.Disconnect();
    }

    @Override
    public void stopConnecting() {
        _pdm.StopConnecting();
    }

    @Override
    public void getPumpStatus() {
        _pdm.UpdateStatus();
    }

    private String getCommentString(OmnipyResult result)
    {
        String comment = "";
        if (result != null)
        {
            if (result.success) {
                long entry_id = -1;
                int lot = -1;
                int tid = -1;
                if (result.response != null && result.response.has("history_entry_id")) {
                    entry_id = result.response.get("history_entry_id").getAsLong();
                }
                if (result.status != null) {
                    lot = result.status.id_lot;
                    tid = result.status.id_t;
                }
                comment = String.format("%d/%d/%d", lot, tid, entry_id);
            }
            else {
                if (result.response != null)
                {
                    comment = result.response.toString();
                }
            }
        }
        return comment;
    }

    private long getHistoryId(OmnipyResult result)
    {
        if (result.status != null)
        {
            return result.status.last_command_db_id;
        }
        return -1;
    }

    @Override
    public PumpEnactResult setNewBasalProfile(Profile profile) {

        PumpEnactResult r = new PumpEnactResult();
        r.enacted = false;
        r.success = false;

        final LoopPlugin loopPlugin = LoopPlugin.getPlugin();
        boolean runLoop = false;
        boolean warnUser = false;

        if (TreatmentsPlugin.getPlugin().isTempBasalInProgress())
        {
            PumpEnactResult cancelResult = this.cancelTempBasal(false);

            if (!cancelResult.success)
            {
                r.comment = "Failed to cancel existing temp basal";
                return r;
            }

            Constraint<Boolean> closedLoopEnabled = MainApp.getConstraintChecker().isClosedLoopAllowed();
            if (loopPlugin.isEnabled(loopPlugin.getType()) && !loopPlugin.isSuspended()
                    && !loopPlugin.isDisconnected() && closedLoopEnabled.value()) {
                runLoop = true;
            }
            else
            {
                warnUser = true;
            }
        }

        OmnipyResult result = _pdm.SetNewBasalProfile(profile);
        if (result != null) {
            r.enacted = result.success;
            r.success = result.success;
            if (result.success) {
                RxBus.INSTANCE.send(new EventDismissNotification(Notification.PROFILE_SET_OK));
                RxBus.INSTANCE.send(new EventDismissNotification(Notification.PROFILE_SET_FAILED));
                Notification notification = new Notification(Notification.PROFILE_SET_OK, MainApp.gs(R.string.profile_set_ok), Notification.INFO, 60);
                RxBus.INSTANCE.send(new EventNewNotification(notification));
                if (warnUser) {
                    RxBus.INSTANCE.send(new EventNewNotification(new Notification(Notification.OMNIPY_TEMP_BASAL_CANCELED,
                            "Temporary basal canceled before setting a new basal profile. Please set temporary basal again." , Notification.NORMAL, 60)));
                }
            } else {
                r.comment = getCommentString(result);
                RxBus.INSTANCE.send(new EventDismissNotification(Notification.PROFILE_SET_OK));
                RxBus.INSTANCE.send(new EventDismissNotification(Notification.PROFILE_SET_FAILED));
                Notification notification = new Notification(Notification.PROFILE_SET_FAILED, "Basal profile not updated", Notification.NORMAL, 60);
                RxBus.INSTANCE.send(new EventNewNotification(notification));
                if (warnUser) {
                    RxBus.INSTANCE.send(new EventNewNotification(new Notification(Notification.OMNIPY_TEMP_BASAL_CANCELED,
                            "Temporary basal canceled trying to set a new basal profile. Please set temporary basal again." , Notification.NORMAL, 60)));
                }
            }
        }
        else
        {
            if (warnUser) {
                RxBus.INSTANCE.send(new EventNewNotification(new Notification(Notification.OMNIPY_TEMP_BASAL_CANCELED,
                        "Temporary basal canceled trying to set a new basal profile. Please set temporary basal again." , Notification.NORMAL, 60)));
            }
        }

        if (runLoop)
        {
            loopPlugin.invoke("OmnipodProfileReset", true);
        }

        return r;
    }

    @Override
    public boolean isThisProfileSet(Profile profile) {
        log.debug("omnipod plugin isThisProfileSet()");
        return _pdm.IsProfileSet(profile);
    }

    @Override
    public long lastDataTime()
    {
        return _pdm.GetLastUpdated();
    }

    @Override
    public double getBaseBasalRate() {
        log.debug("omnipod plugin GetBaseBasalRate()");
        return _pdm.GetBaseBasalRate();
    }

    @Override
    public double getReservoirLevel() {
        return _pdm.GetReservoirLevel();
    }

    @Override
    public int getBatteryLevel() {
        return _pdm.getBatteryLevel();
    }

    @Override
    public PumpEnactResult deliverTreatment(DetailedBolusInfo detailedBolusInfo) {

        PumpEnactResult r = new PumpEnactResult();
        r.enacted = false;
        r.success = false;

        BigDecimal units = _pdm.GetExactInsulinUnits(detailedBolusInfo.insulin);
        OmnipyResult result = _pdm.Bolus(units);
        if (result != null)
        {
            r.enacted = result.success;
            r.success = result.success;
            if (!result.success) {
                r.bolusDelivered = 0;
                r.comment = getCommentString(result);
            }
            else {
                detailedBolusInfo.deliverAt = _pdm.GetLastResultDate();
                detailedBolusInfo.pumpId = getHistoryId(result);
                TreatmentsPlugin.getPlugin().addToHistoryTreatment(detailedBolusInfo, false);

                r.carbsDelivered = detailedBolusInfo.carbs;

                _runningBolusInfo = detailedBolusInfo;
                Double delivering = 0.05d;

                while (delivering < detailedBolusInfo.insulin) {
                    EventOverviewBolusProgress bolusingEvent = EventOverviewBolusProgress.INSTANCE;
                    bolusingEvent.setStatus(String.format(MainApp.gs(R.string.bolusdelivering), delivering));
                    bolusingEvent.setPercent(Math.min((int) (delivering / detailedBolusInfo.insulin * 100), 100));
                    RxBus.INSTANCE.send(bolusingEvent);
                    delivering += 0.05d;
                    SystemClock.sleep(2000);
                }
                SystemClock.sleep(200);
                EventOverviewBolusProgress bolusingEvent = EventOverviewBolusProgress.INSTANCE;
                bolusingEvent.setStatus(String.format(MainApp.gs(R.string.bolusdelivered), detailedBolusInfo.insulin));
                bolusingEvent.setPercent(100);
                RxBus.INSTANCE.send(bolusingEvent);
                SystemClock.sleep(1000);
                if (L.isEnabled(L.PUMPCOMM))
                    log.debug("Delivering treatment insulin: " + detailedBolusInfo.insulin + "U carbs: " + detailedBolusInfo.carbs + "g " + result);
                RxBus.INSTANCE.send(new EventOmnipodUpdateGui());
            }
        }
        return r;
    }

    @Override
    public void stopBolusDelivering() {
        log.debug("omnipod plugin StopBolusDelivering()");
        if (_runningBolusInfo != null) {
            _pdm.CancelBolus(result ->
            {
                double canceled = -1d;
                if (result.success) {
                    canceled = result.status.insulin_canceled;
                }

                EventOverviewBolusProgress bolusingEvent = EventOverviewBolusProgress.INSTANCE;

                Double supposedToDeliver = _runningBolusInfo.insulin;
                if (canceled <= 0d) {
                    if (bolusingEvent != null)
                        bolusingEvent.setStatus(String.format("Couldn't stop bolus in time, delivered: %f.2u", supposedToDeliver));
                } else {
                    if (bolusingEvent != null)
                        bolusingEvent.setStatus(MainApp.gs(R.string.bolusstopped));
                    _runningBolusInfo.insulin = supposedToDeliver - canceled;
                }
                if (bolusingEvent != null) {
                    RxBus.INSTANCE.send(bolusingEvent);
                    RxBus.INSTANCE.send(new EventOmnipodUpdateGui());
                }
                SystemClock.sleep(100);
                if (canceled > 0d)
                    _runningBolusInfo.notes = String.format("Delivery stopped at %f.2u. Original bolus request was: %f.2u", supposedToDeliver - canceled, supposedToDeliver);
                TreatmentsPlugin.getPlugin().addToHistoryTreatment(_runningBolusInfo, true);
            });

            EventOverviewBolusProgress bolusingEvent = EventOverviewBolusProgress.INSTANCE;
            if (bolusingEvent != null) {
                bolusingEvent.setStatus(MainApp.gs(R.string.bolusstopping));
                RxBus.INSTANCE.send(bolusingEvent);
                RxBus.INSTANCE.send(new EventOmnipodUpdateGui());
            }
        }
    }

    @Override
    public PumpEnactResult setTempBasalAbsolute(Double absoluteRate, Integer durationInMinutes, Profile profile, boolean enforceNew) {
        PumpEnactResult r = new PumpEnactResult();
        r.enacted = false;
        r.success = false;

        BigDecimal iuRate = _pdm.GetExactInsulinUnits(absoluteRate);
        BigDecimal durationHours = _pdm.GetExactHourUnits(durationInMinutes);
        OmnipyResult result = _pdm.SetTempBasal(iuRate, durationHours);

        if (result != null) {
            r.enacted = result.success;
            r.success = result.success;
            if (result.success)
            {
                r.absolute = iuRate.doubleValue();
                r.duration = durationHours.multiply(new BigDecimal(60)).intValue();
                r.isPercent = false;

                TemporaryBasal tempBasal = new TemporaryBasal()
                        .date(_pdm.GetLastResultDate())
                        .absolute(r.absolute)
                        .duration(r.duration)
                        .pumpId(getHistoryId(result))
                        .source(Source.USER);
                TreatmentsPlugin.getPlugin().addToHistoryTempBasal(tempBasal);
                if (L.isEnabled(L.PUMPCOMM))
                    log.debug("Setting temp basal absolute: " + result);
            }
            else
            {
                r.comment = getCommentString(result);
            }
        }
        RxBus.INSTANCE.send(new EventOmnipodUpdateGui());
        return r;
    }

    @Override
    public PumpEnactResult cancelTempBasal(boolean enforceNew) {
        PumpEnactResult r = new PumpEnactResult();
        r.enacted = false;
        r.success = false;

        OmnipyResult result = _pdm.CancelTempBasal();
        if (result != null) {
            r.enacted = result.success;
            r.success = result.success;
            if (result.success) {
                r.isTempCancel = true;
                if (TreatmentsPlugin.getPlugin().isTempBasalInProgress()) {
                    TemporaryBasal tempStop = new TemporaryBasal().date(_pdm.GetLastResultDate()).source(Source.USER);
                    TreatmentsPlugin.getPlugin().addToHistoryTempBasal(tempStop);
                    RxBus.INSTANCE.send(new EventOmnipodUpdateGui());
                }
            }
            else
            {
                r.comment = getCommentString(result);
            }
        }
        return r;
    }

    @Override
    public JSONObject getJSONStatus(Profile profile, String profileName) {
        log.debug("GetJSONStatus()");
        long now = System.currentTimeMillis();

        JSONObject pump = new JSONObject();
        JSONObject battery = new JSONObject();
        JSONObject status = new JSONObject();
        JSONObject extended = new JSONObject();
        try {
            battery.put("percent", getBatteryLevel());
            status.put("status", _pdm.getPodStatusText());
            extended.put("Version", BuildConfig.VERSION_NAME + "-" + BuildConfig.BUILDVERSION);
            try {
                extended.put("ActiveProfile", profileName);
            } catch (Exception ignored) {
            }
            TemporaryBasal tb = TreatmentsPlugin.getPlugin().getTempBasalFromHistory(now);
            if (tb != null) {
                extended.put("TempBasalAbsoluteRate", tb.tempBasalConvertedToAbsolute(now, profile));
                extended.put("TempBasalStart", DateUtil.dateAndTimeString(tb.date));
                extended.put("TempBasalRemaining", tb.getPlannedRemainingMinutes());
            }
            ExtendedBolus eb = TreatmentsPlugin.getPlugin().getExtendedBolusFromHistory(now);
            if (eb != null) {
                extended.put("ExtendedBolusAbsoluteRate", eb.absoluteRate());
                extended.put("ExtendedBolusStart", DateUtil.dateAndTimeString(eb.date));
                extended.put("ExtendedBolusRemaining", eb.getPlannedRemainingMinutes());
            }
            status.put("timestamp", DateUtil.toISOString(now));

            pump.put("battery", battery);
            pump.put("status", status);
            pump.put("extended", extended);
            pump.put("reservoir", getReservoirLevel());
            pump.put("clock", DateUtil.toISOString(now));
        } catch (JSONException e) {
            log.error("Unhandled exception", e);
        }
        return pump;
    }

    @Override
    public PumpEnactResult setTempBasalPercent(Integer percent, Integer durationInMinutes, Profile profile, boolean enforceNew) {
        log.debug("omnipod plugin SetTempBasalPercent()");
        PumpEnactResult per = new PumpEnactResult();
        per.enacted = false;
        per.success = false;
        return per;
    }

    @Override
    public PumpEnactResult setExtendedBolus(Double insulin, Integer durationInMinutes) {
        log.debug("omnipod plugin SetExtendedBolus()");
        PumpEnactResult per = new PumpEnactResult();
        per.enacted = false;
        per.success = false;
        return per;
    }

    @Override
    public PumpEnactResult cancelExtendedBolus() {
        log.debug("CancelExtendedBolus()");
        PumpEnactResult per = new PumpEnactResult();
        per.enacted = false;
        per.success = false;
        return per;
    }

   // @Override
    public String deviceID() {
        log.debug("DeviceID()");
        return _pdm.GetPodId();
    }

    @Override
    public PumpDescription getPumpDescription() {
        //log.debug("getPumpDescription()");
        return pumpDescription;
    }

    @Override
    public String shortStatus(boolean veryShort) {
        log.debug("shortStatus()");
        return _pdm.GetStatusShort();
    }

    @Override
    public boolean isFakingTempsByExtendedBoluses() {
        //log.debug("isFaking()");
        return false;
    }

    @Override
    public PumpEnactResult loadTDDs() {
        log.debug("loadTDDs()");
        PumpEnactResult per = new PumpEnactResult();
        per.enacted = false;
        per.success = false;
        return per;
    }

    @Override
    public boolean canHandleDST() {
        return true;
    }

    @Override
    public List<CustomAction> getCustomActions() {
        return null;
    }

    @Override
    public void executeCustomAction(CustomActionType customActionType) {

    }

    @Override
    public ManufacturerType manufacturer() {
        return ManufacturerType.Insulet;
    }

    @Override
    public PumpType model() {
        return PumpType.Omnipy_Omnipod;
    }

    @Override
    public String serialNumber() {
        return InstanceId.INSTANCE.instanceId(); // TODO replace by real serial
    }

    @Override
    public void timeDateOrTimeZoneChanged() {

    }

}
