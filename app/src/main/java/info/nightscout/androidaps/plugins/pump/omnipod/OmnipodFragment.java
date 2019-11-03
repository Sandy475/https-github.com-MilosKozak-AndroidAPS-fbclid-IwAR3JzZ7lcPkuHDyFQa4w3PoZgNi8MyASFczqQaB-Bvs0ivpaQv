package info.nightscout.androidaps.plugins.pump.omnipod;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.squareup.otto.Subscribe;

import java.util.Calendar;
import java.util.Objects;
import java.util.TimeZone;

import butterknife.OnClick;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.plugins.common.SubscriberFragment;
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.pump.omnipod.api.OmnipodStatus;
import info.nightscout.androidaps.plugins.pump.omnipod.api.OmnipyRestApi;
import info.nightscout.androidaps.plugins.pump.omnipod.api.rest.OmnipyCallback;
import info.nightscout.androidaps.plugins.pump.omnipod.api.rest.OmnipyResult;
import info.nightscout.androidaps.plugins.pump.omnipod.events.EventOmnipodUpdateGui;

public class OmnipodFragment extends SubscriberFragment implements View.OnClickListener {

    public OmnipodFragment() {
        // Required empty public constructor
    }

    public static OmnipodFragment newInstance() {
        return new OmnipodFragment();
    }

    private OmnipodPdm _pdm;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        OmnipodPlugin op = OmnipodPlugin.getPlugin();
        _pdm = op.getPdm();
    }

    @Subscribe
    public void onStatusEvent(final EventOmnipodUpdateGui ev) {
        Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(() -> updateGUI());
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_omnipod, container, false);

        view.findViewById(R.id.omnipy_btn_check_connection).setOnClickListener(this);
        view.findViewById(R.id.omnipy_btn_update_status).setOnClickListener(this);
        view.findViewById(R.id.omnipy_btn_clear_alerts).setOnClickListener(this);
        view.findViewById(R.id.omnipy_btn_shutdown_remote_host).setOnClickListener(this);
        view.findViewById(R.id.omnipy_btn_restart_remote_host).setOnClickListener(this);
        view.findViewById(R.id.omnipy_btn_deactivate_pod).setOnClickListener(this);
        view.findViewById(R.id.omnipy_btn_archive_pod).setOnClickListener(this);
        view.findViewById(R.id.omnipy_btn_activate_pod).setOnClickListener(this);
        view.findViewById(R.id.omnipy_btn_start_pod).setOnClickListener(this);
        return view;
    }



    private void UpdateStatus(Button b) {
        OmnipyRestApi rest = _pdm.GetRestApi();
        DialogMessage(MainApp.gs(R.string.omnipod_UpdateStatus_Requesting_status_update));      //"Requesting status update"
        rest.UpdateStatus(result -> {
            CloseDialog();
            if (result.success) {
                DialogMessageWithOK(MainApp.gs(R.string.omnipod_UpdateStatus_Status_updated));                          //"Status updated"
            } else {
                if (result.response != null)
                    DialogMessageWithOK(MainApp.gs(R.string.omnipod_UpdateStatus_Status_update_failed1) + "\n " + result.response.toString());            //"Status update failed:"
                else
                    DialogMessageWithOK(MainApp.gs(R.string.omnipod_UpdateStatus_Status_update_failed2));                //"Status update failed"
            }
        });
    }

    private void CheckConnection(Button b) {
        OmnipyRestApi rest = _pdm.GetRestApi();
        DialogMessage(MainApp.gs(R.string.omnipod_CheckConnection_Trying_to_connect));         //"Trying to connect"
        rest.CheckAuthentication(result -> {
            if (result.success) {
                DialogMessageWithOK(MainApp.gs(R.string.omnipod_CheckConnection_Connection_successful));      //"Connection successful!"
            } else {
                if (result.response != null)
                    DialogMessageWithOK(MainApp.gs(R.string.omnipod_CheckConnection_Connection_failed1) + "\n " + result.response.toString());       //"Connection failed:"
                else
                    DialogMessageWithOK(MainApp.gs(R.string.omnipod_CheckConnection_Connection_failed2));       //"Connection failed"
            }
        });
    }

    private void Shutdown(Button b) {
        OmnipyRestApi rest = _pdm.GetRestApi();
        DialogMessage(MainApp.gs(R.string.omnipod_Shutdown_Requesting_shutdown));       //"Requesting shutdown"
        rest.Shutdown (result -> {
            if (result.success) {
                DialogMessageWithOK(MainApp.gs(R.string.omnipod_Shutdown_Shutdown_request_sent));          //"Shutdown request sent."
            } else {
                if (result.response != null)
                    DialogMessageWithOK(MainApp.gs(R.string.omnipod_Shutdown_Shutdown_failed1) + "\n " + result.response.toString());         //"Shutdown failed:"
                else
                    DialogMessageWithOK(MainApp.gs(R.string.omnipod_Shutdown_Shutdown_failed2));         //"Shutdown failed"
            }
        });
    }

    private void Restart(Button b) {
        OmnipyRestApi rest = _pdm.GetRestApi();
        DialogMessage(MainApp.gs(R.string.omnipod_Restart_Requesting_Restart));            //"Requesting restart"
        rest.Restart (result -> {
            if (result.success) {
                DialogMessageWithOK(MainApp.gs(R.string.omnipod_Restart_Restart_request_sent));       //"Restart request sent."
            } else {
                if (result.response != null)
                    DialogMessageWithOK(MainApp.gs(R.string.omnipod_Restart_Restart_failed1) + "\n " + result.response.toString());      //"Restart failed:"
                else
                    DialogMessageWithOK(MainApp.gs(R.string.omnipod_Restart_Restart_failed2));      //"Restart failed"
            }
        });
    }


    private void ClearAlerts(Button b) {
        OmnipyRestApi rest = _pdm.GetRestApi();
        int alerts = _pdm.getStatus().state_alert;
        DialogMessage(MainApp.gs(R.string.omnipod_ClearAlerts_Requesting_clear_alerts));       //"Requesting clear alerts"
        rest.AcknowledgeAlerts ( alerts, result -> {
            if (result.success) {
                DialogMessageWithOK(MainApp.gs(R.string.omnipod_ClearAlerts_Alerts_cleared));      //"Alerts cleared"
            } else {
                if (result.response != null)
                    DialogMessageWithOK(MainApp.gs(R.string.omnipod_ClearAlerts_Clear_alerts_failed1) + "\n " + result.response.toString());     //"Clear alerts failed:"
                else
                    DialogMessageWithOK(MainApp.gs(R.string.omnipod_ClearAlerts_Clear_alerts_failed2));     //"Clear alerts failed"
            }
        });
    }

    private void DeactivatePod(Button b){
        OmnipyRestApi rest = _pdm.GetRestApi();
        DialogMessage(MainApp.gs(R.string.omnipod_DeactivatePod_Deactivating_pod));          //"Deactivating pod"
        rest.DeactivatePod (result -> {
            if (result.success) {
                DialogMessageWithOK(MainApp.gs(R.string.omnipod_DeactivatePod_Pod_deactivated));       //Pod deactivated
            } else {
                if (result.response != null)
                    DialogMessageWithOK(MainApp.gs(R.string.omnipod_DeactivatePod_Deactivate_pod_failed1) + "\n " + result.response.toString());            //"Deactivate pod failed:"
                else
                    DialogMessageWithOK(MainApp.gs(R.string.omnipod_DeactivatePod_Deactivate_pod_failed2));        //Deactivate pod failed
            }
        });
    }

    public void ArchivePod(View view)
    {
        OmnipyRestApi rest = _pdm.GetRestApi();
        DialogMessage(MainApp.gs(R.string.omnipod_ArchivePod_Archiving_pod));     //"Archiving pod"
        rest.ArchivePod (result -> {
            if (result.success) {
                DialogMessageWithOK(MainApp.gs(R.string.omnipod_ArchivePod_Pod_archived));        //"Pod archived"
            } else {
                if (result.response != null)
                    DialogMessageWithOK(MainApp.gs(R.string.omnipod_ArchivePod_Archiving_pod_failed1) + "\n " + result.response.toString());      //"Archive pod failed:"
                else
                    DialogMessageWithOK(MainApp.gs(R.string.omnipod_ArchivePod_Archiving_pod_failed2));          //"Archive pod failed"
            }
        });
    }


    public void ActivatePod(View view)
    {
        Profile profile = ProfileFunctions.getInstance().getProfile();
        if (profile == null)
        {
            DialogMessageWithOK(MainApp.gs(R.string.omnipod_activate_err_no_profile_set));
            return;
        }
        OmnipyRestApi rest = _pdm.GetRestApi();
        Confirm(MainApp.gs(R.string.omnipod_ActivatePod_1) + "\n"  + "\n"  +           //"Please fill the pod with insulin before starting with activation process."
                MainApp.gs(R.string.omnipod_ActivatePod_2),           //"Have you filled the pod with insulin and heard two beeps while filling it?"
                () -> {
                    Confirm(MainApp.gs(R.string.omnipod_ActivatePod_3) + "\n"  + "\n"  +       //"Have you positioned the RileyLink and the Pod as close to each other as possible?"
                            MainApp.gs(R.string.omnipod_ActivatePod_4),                                                       //"Once you click Yes, activation will begin."
                            () -> {
                                DialogMessage(MainApp.gs(R.string.omnipod_ActivatePod_5) + "\n"  + "\n"  +                                   //"Pairing pod with omnipy"
                                        MainApp.gs(R.string.omnipod_ActivatePod_6) +          //"If this takes longer than 30 seconds, try changing positions of the pod and RL"
                                        MainApp.gs(R.string.omnipod_ActivatePod_7));      //" ensuring there is a small gap between the two of them.\nOmnipy will try to pair with the pod for up to 2 minutes."

                                TimeZone tz = profile.getTimeZone();
                                int offset_minutes = (tz.getRawOffset() + tz.getDSTSavings()) / (60 * 1000);

                                rest.PairPod(offset_minutes, result ->
                                {
                                    if (result.success) {
                                        DialogMessage(MainApp.gs(R.string.omnipod_ActivatePod_8) +  "\n" + "\n" +                        //"Pairing successful."
                                                MainApp.gs(R.string.omnipod_ActivatePod_9) +          //"Setting pod variables and priming cannula now."
                                                "\n" + "\n" + MainApp.gs(R.string.omnipod_ActivatePod_10));                //"This can take up to two minutes."
                                        rest.ActivatePod(result2 -> {
                                            if (result2.success) {
                                                DialogMessageWithOK(MainApp.gs(R.string.omnipod_ActivatePod_11)  + "\n"  + "\n" +                     //"Pod has been primed and activated successfully."
                                                        MainApp.gs(R.string.omnipod_ActivatePod_12) + "\n"  +             //"Please prepare the site for insertion, remove the plastic cover on the pod. If the canula is visible, please deactivate this pod."
                                                        MainApp.gs(R.string.omnipod_ActivatePod_13)         //"Otherwise peel off the adhesive strips and apply the pod on the skin.\nWhen you're finished, use the START button to start the injection process."
                                                );
                                            }
                                            else {
                                                if (result2.response != null)
                                                    DialogMessageWithOK(MainApp.gs(R.string.omnipod_ActivatePod_14) + "\n " + result2.response.toString());     //"Activation failed, please try again. Error:"
                                                else
                                                    DialogMessageWithOK(MainApp.gs(R.string.omnipod_ActivatePod_15));            //"Activation failed, please try again."
                                            }
                                        });
                                    } else {
                                        if (result.response != null)
                                            DialogMessageWithOK(MainApp.gs(R.string.omnipod_ActivatePod_16) + "\n " + result.response.toString());     //"Pairing failed, please try again. Error:"
                                        else
                                            DialogMessageWithOK(MainApp.gs(R.string.omnipod_ActivatePod_17));           //"Pairing failed, please try again."
                                    }

                                });
                            }
                            );
                }
                );
    }


    public void StartPod(View view) {
        OmnipyRestApi rest = _pdm.GetRestApi();
        Confirm(MainApp.gs(R.string.omnipodStartPod_1) + "\n" ,            //"When you're ready, click Yes to start the insertion and basal delivery process."
                () -> {
                    Profile profile = ProfileFunctions.getInstance().getProfile();
                    DialogMessage(MainApp.gs(R.string.omnipodStartPod_2));              //"Starting the pod"
                    rest.StartPod(_pdm.getBasalScheduleFromProfile(profile), result -> {
                        if (result.success) {
                            DialogMessageWithOK(MainApp.gs(R.string.omnipodStartPod_3));            //"Pod started succesfully."
                        } else {
                            if (result.response != null)
                                DialogMessageWithOK(MainApp.gs(R.string.omnipodStartPod_4) + "\n " + result.response.toString());            //"Starting the pod failed:"
                            else
                                DialogMessageWithOK(MainApp.gs(R.string.omnipodStartPod_5));         //"Starting the pod failed"
                        }
                    });
                }
        );
    }


    @Override
    protected void updateGUI() {
        View vw = this.getView();

        TextView tv = vw.findViewById(R.id.omnipy_txt_connection_status);
        tv.setText(_pdm.getConnectionStatusText());

        tv = vw.findViewById(R.id.omnipy_txt_pod_status);
        tv.setText(_pdm.getPodStatusText());

        OmnipodStatus pod = _pdm.getStatus();
        OmnipyRestApi rest = _pdm.getRestApi();

        Button b = (Button)vw.findViewById(R.id.omnipy_btn_check_connection);
        b.setEnabled(rest.isConfigured() & rest.isConnectable());

        b = vw.findViewById(R.id.omnipy_btn_shutdown_remote_host);
        b.setEnabled(rest.isConfigured() & rest.isConnectable() & rest.isAuthenticated());

        b = vw.findViewById(R.id.omnipy_btn_restart_remote_host);
        b.setEnabled(rest.isConfigured() & rest.isConnectable() & rest.isAuthenticated());

        b = vw.findViewById(R.id.omnipy_btn_update_status);
        b.setEnabled(rest.isConfigured() & rest.isConnectable() & rest.isAuthenticated()
                & (pod.state_progress > 0) & (pod.state_progress<15) & pod.radio_address != 0);

        b = vw.findViewById(R.id.omnipy_btn_clear_alerts);
        b.setEnabled(rest.isConfigured() & rest.isConnectable() & rest.isAuthenticated()
                & (pod.state_alert > 0) & (pod.state_progress >= 8) & (pod.state_progress<15) & pod.radio_address != 0);

        b = vw.findViewById(R.id.omnipy_btn_deactivate_pod);
        b.setEnabled(rest.isConfigured() & rest.isConnectable() & rest.isAuthenticated() &
                (pod.state_progress >= 3) & (pod.state_progress<15) & pod.radio_address != 0);

        b = vw.findViewById(R.id.omnipy_btn_archive_pod);
        b.setEnabled(rest.isConfigured() & rest.isConnectable() & rest.isAuthenticated() &
                pod.radio_address != 0);

        b = vw.findViewById(R.id.omnipy_btn_activate_pod);
        b.setEnabled(rest.isConfigured() & rest.isConnectable() & rest.isAuthenticated() &
                pod.state_progress < 5);

        b = vw.findViewById(R.id.omnipy_btn_start_pod);
        b.setEnabled(rest.isConfigured() & rest.isConnectable() & rest.isAuthenticated() &
                (pod.state_progress == 5) & pod.radio_address != 0);
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        switch(id)
        {
            case R.id.omnipy_btn_check_connection:
                CheckConnection((Button)view);
                break;
            case R.id.omnipy_btn_update_status:
                UpdateStatus((Button)view);
                break;
            case R.id.omnipy_btn_clear_alerts:
                ClearAlerts((Button)view);
                break;
            case R.id.omnipy_btn_restart_remote_host:
                Confirm(MainApp.gs(R.string.omnipod_restart_Pi_prompt), () -> {            //"Are you sure you want to restart the omnipy host?"
                    Restart((Button)view); });
                break;
            case R.id.omnipy_btn_shutdown_remote_host:
                Confirm(MainApp.gs(R.string.omnipod_shutdown_pi_prompt), () -> {          //"Are you sure you want to shut down the omnipy host?"
                    Shutdown((Button)view); });
                break;
            case R.id.omnipy_btn_deactivate_pod:
                Confirm(MainApp.gs(R.string.omnipod_pod_deactivation__prompt), () -> {        //"Are you sure you want to deactivate the pod? The pod will be turned off completely and you will not be able to access it."
                    DeactivatePod((Button)view); });
                break;
            case R.id.omnipy_btn_archive_pod:
                Confirm(MainApp.gs(R.string.omnipod_pod_archive_prompt), () -> {       //"Are you sure you want to archive the pod without deactivating it? The pod will continue to deliver basals as programmed but you will not be able to access it."
                    ArchivePod((Button)view); });
                break;
            case R.id.omnipy_btn_activate_pod:
                Confirm(MainApp.gs(R.string.omnipod_pod_activation_prompt), () -> {     //"Are you sure you want to activate a new pod?"
                    ActivatePod((Button)view); });
                break;
            case R.id.omnipy_btn_start_pod:
                Confirm(MainApp.gs(R.string.omnipod_pod_start_prompt), () -> {     //"Are you sure you want to start this pod?"
                    StartPod((Button)view); });
                break;
            default:
                break;
        }
    }

    private AlertDialog _currentDialog;
    private void DialogMessage(String text)
    {
        CloseDialog();
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext()).setCancelable(false);
        builder.setMessage(text);
        _currentDialog = builder.show();
    }

    private void DialogMessageWithOK(String text)
    {
        CloseDialog();
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext()).setCancelable(false);
        _currentDialog = builder.setMessage(text).setPositiveButton("OK", null).show();
    }

    private void CloseDialog()
    {
        if(_currentDialog != null)
        {
            _currentDialog.hide();
            _currentDialog = null;
        }
    }

    private void Confirm(String text, Runnable ifYes) {
        CloseDialog();
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext()).setCancelable(false);
        DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                switch(i)
                {
                    case DialogInterface.BUTTON_POSITIVE:
                        ifYes.run();
                        break;

                    case DialogInterface.BUTTON_NEGATIVE:
                        break;
                }
            }
        };

        _currentDialog = builder.setMessage(text).setPositiveButton(MainApp.gs(R.string.omnipod_Yes_prompt), listener)     //"Yes"
                .setNegativeButton(MainApp.gs(R.string.omnipod_No_prompt), listener).show();                              //"No"
    }

}
