package com.veken0m.bitcoinium;

import android.app.IntentService;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.widget.RemoteViews;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.veken0m.mining.bitminter.BitMinterData;
import com.veken0m.mining.btcguild.BTCGuild;
import com.veken0m.mining.eligius.Eligius;
import com.veken0m.mining.eligius.EligiusBalance;
import com.veken0m.mining.emc.EMC;
import com.veken0m.mining.fiftybtc.FiftyBTC;
import com.veken0m.mining.slush.Slush;
import com.veken0m.mining.slush.Workers;
import com.veken0m.utils.Constants;
import com.veken0m.utils.CurrencyUtils;
import com.veken0m.utils.Utils;
import com.xeiam.xchange.Exchange;
import com.xeiam.xchange.ExchangeFactory;
import com.xeiam.xchange.ExchangeSpecification;
import com.xeiam.xchange.cexio.CexIOExchange;
import com.xeiam.xchange.cexio.dto.account.CexIOBalance;
import com.xeiam.xchange.cexio.dto.account.CexIOBalanceInfo;
import com.xeiam.xchange.cexio.dto.account.GHashIOHashrate;
import com.xeiam.xchange.cexio.service.polling.CexIOAccountServiceRaw;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;

import java.io.InputStreamReader;
import java.util.List;

//import com.veken0m.utils.KarmaAdsUtils;

public class MinerWidgetProvider extends BaseWidgetProvider
{
    private static float hashRate = 0.0F;
    private static float btcBalance = 0.0F;

    @Override
    public void onReceive(Context context, Intent intent)
    {
        super.onReceive(context, intent);

        if (Constants.REFRESH.equals(intent.getAction()))
            onUpdate(context, null, null);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds)
    {
        // onUpdate called upon create or when forced refresh by user. Use this to create a set refresh service.
        setRefreshServiceAlarm(context, MinerUpdateService.class);
    }

    /**
     * This class lets us refresh the widget whenever we want to
     */
    public static class MinerUpdateService extends IntentService
    {
        public MinerUpdateService()
        {
            super("MinerWidgetProvider$MinerUpdateService");
        }

        public void buildUpdate()
        {
            AppWidgetManager widgetManager = AppWidgetManager.getInstance(this);
            ComponentName widgetComponent = new ComponentName(this, MinerWidgetProvider.class);

            readGeneralPreferences(this);

            if (widgetManager != null && (!pref_wifiOnly || Utils.isWiFiAvailable(this)))
            {
                int[] widgetIds = widgetManager.getAppWidgetIds(widgetComponent);
                for (int appWidgetId : widgetIds)
                {
                    // Load Widget configuration
                    String miningPool = MinerWidgetConfigureActivity.loadMiningPoolPref(this, appWidgetId);

                    if (miningPool == null) continue; // skip to next widget

                    RemoteViews views = new RemoteViews(this.getPackageName(), R.layout.minerappwidget);
                    setTapBehaviour(appWidgetId, miningPool, views);

                    Boolean pref_minerDownAlert = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
                            miningPool.toLowerCase() + "AlertPref", false);

                    if (getMinerInfo(miningPool))
                    {
                        views.setTextViewText(R.id.widgetMinerHashrate, Utils.formatHashrate(hashRate));
                        views.setTextViewText(R.id.widgetMiner, miningPool);
                        views.setTextViewText(R.id.widgetBTCPayout,
                                CurrencyUtils.formatPayout(btcBalance, pref_widgetPayoutUnits, "BTC"));

                        if ((hashRate < 0.01) && pref_minerDownAlert)
                            createMinerDownNotification(this, miningPool);

                        String refreshedTime = getString(R.string.update_short) + Utils.getCurrentTime(this);
                        views.setTextViewText(R.id.refreshtime, refreshedTime);

                        updateWidgetTheme(views);
                    }
                    else
                    {
                        views.setTextColor(R.id.refreshtime, pref_enableWidgetCustomization ? pref_widgetRefreshFailedColor : Color.RED);
                    }
                    if (widgetManager != null) widgetManager.updateAppWidget(appWidgetId, views);
                }
            }
        }

        public Boolean getMinerInfo(String sMiningPool)
        {
            if (prefs == null) prefs = PreferenceManager.getDefaultSharedPreferences(this);

            HttpClient client = new DefaultHttpClient();
            ObjectMapper mapper = new ObjectMapper();

            // reset variables
            btcBalance = 0;
            hashRate = 0;

            try
            {
                // TODO: fix this ugly mess
                if (sMiningPool.equalsIgnoreCase("BitMinter"))
                {
                    String pref_apiKey = prefs.getString("bitminterKey", "");

                    HttpGet post = new HttpGet(
                            "https://bitminter.com/api/users" + "?key="
                                    + pref_apiKey
                    );

                    HttpResponse response = client.execute(post);
                    BitMinterData data = mapper.readValue(new InputStreamReader(response
                                    .getEntity().getContent(), "UTF-8"),
                            BitMinterData.class
                    );
                    btcBalance = data.getBalances().getBTC();
                    hashRate = data.getHash_rate();
                    return true;
                }
                else if (sMiningPool.equalsIgnoreCase("EclipseMC"))
                {

                    String pref_apiKey = prefs.getString("emcKey", "");
                    HttpGet post = new HttpGet(
                            "https://eclipsemc.com/api.php?key=" + pref_apiKey
                                    + "&action=userstats"
                    );

                    HttpResponse response = client.execute(post);
                    EMC data = mapper.readValue(new InputStreamReader(response
                            .getEntity().getContent(), "UTF-8"), EMC.class);

                    btcBalance = data.getData().getUser().getConfirmed_rewards();

                    for (int i = 0; i < data.getWorkers().size(); i++)
                    {
                        String hashRateString = data.getWorkers().get(i).getHash_rate();
                        // EclipseMC hashrate contains units. Strip them off
                        // And convert all GH/s to MH/s
                        float temp_hashRate;
                        if (!hashRateString.contentEquals(" "))
                        {
                            String hash_rate[] = hashRateString.split(" ");
                            temp_hashRate = Float.parseFloat(hash_rate[0]);

                            if (hash_rate[1].contains("G"))
                                temp_hashRate *= 1000;
                            else if (hash_rate[1].contains("T"))
                                temp_hashRate *= 1000000;
                        }
                        else
                        {
                            // empty hashrate, set to 0;
                            temp_hashRate = 0;
                        }
                        hashRate += temp_hashRate;
                    }
                    return true;
                }
                else if (sMiningPool.equalsIgnoreCase("Slush"))
                {
                    String pref_apiKey = prefs.getString("slushKey", "");

                    HttpGet post = new HttpGet(
                            "https://mining.bitcoin.cz/accounts/profile/json/"
                                    + pref_apiKey
                    );

                    HttpResponse response = client.execute(post);
                    Slush data = mapper.readValue(new InputStreamReader(response
                            .getEntity().getContent(), "UTF-8"), Slush.class);
                    btcBalance = data.getConfirmed_reward();

                    Workers workers = data.getWorkers();

                    for (int i = 0; i < workers.getWorkers().size(); i++)
                    {
                        hashRate += workers.getWorker(i).getHashrate();
                    }
                    return true;
                }
                else if (sMiningPool.equalsIgnoreCase("50BTC"))
                {
                    String pref_apiKey = prefs.getString("50BTCKey", "");

                    HttpGet post = new HttpGet("https://50btc.com/api/" + pref_apiKey);
                    HttpResponse response = client.execute(post);
                    FiftyBTC data = mapper.readValue(new InputStreamReader(response
                                    .getEntity().getContent(), "UTF-8"),
                            FiftyBTC.class
                    );
                    btcBalance = data.getUser().getConfirmed_rewards();
                    hashRate = data.getUser().getHash_rate();

                    return true;
                }
                else if (sMiningPool.equalsIgnoreCase("BTCGuild"))
                {
                    String pref_apiKey = prefs.getString("btcguildKey", "");

                    HttpGet post = new HttpGet("https://www.btcguild.com/api.php?api_key="
                            + pref_apiKey);
                    HttpResponse response = client.execute(post);
                    BTCGuild data = mapper
                            .readValue(new InputStreamReader(response
                                            .getEntity().getContent(), "UTF-8"),
                                    BTCGuild.class
                            );
                    btcBalance = data.getUser().getUnpaid_rewards();
                    hashRate = 0.0f;

                    List<com.veken0m.mining.btcguild.Worker> workers = data.getWorkers()
                            .getWorkers();
                    for (com.veken0m.mining.btcguild.Worker worker : workers)
                    {
                        hashRate += worker.getHash_rate();
                    }
                    return true;
                }
                else if (sMiningPool.equalsIgnoreCase("Eligius"))
                {
                    String pref_apiKey = prefs.getString("eligiusKey", "");

                    // NOTE: eligius.st does not use HTTPS
                    HttpGet post = new HttpGet(
                            "http://eligius.st/~wizkid057/newstats/hashrate-json.php/"
                                    + pref_apiKey
                    );
                    HttpResponse response = client.execute(post);
                    mapper.setSerializationInclusion(Include.NON_NULL);

                    Eligius data = mapper.readValue(new InputStreamReader(response
                                    .getEntity().getContent(), "UTF-8"),
                            Eligius.class
                    );

                    hashRate = data.get256().getHashrate() / 1000000;

                    post = new HttpGet("http://eligius.st/~luke-jr/balance.php?addr=" + pref_apiKey);

                    EligiusBalance data2 = mapper
                            .readValue(new InputStreamReader(client.execute(post)
                                            .getEntity().getContent(), "UTF-8"),
                                    EligiusBalance.class
                            );

                    btcBalance = data2.getConfirmed() / 100000000;

                    return true;
                }
                else if (sMiningPool.equalsIgnoreCase("GHash.IO"))
                {
                    Exchange cexioExchange = ExchangeFactory.INSTANCE.createExchange(CexIOExchange.class.getName());

                    ExchangeSpecification specs = new ExchangeSpecification(CexIOExchange.class.getName());

                    String pref_ghashioUsername = prefs.getString("ghashioUsername", "");
                    String pref_ghashioAPIKey = prefs.getString("ghashioAPIKey", "");
                    String pref_ghashioSecretKey = prefs.getString("ghashioSecretKey", "");

                    specs.setApiKey(pref_ghashioAPIKey);
                    specs.setSecretKey(pref_ghashioSecretKey);
                    specs.setUserName(pref_ghashioUsername);
                    cexioExchange.applySpecification(specs);

                    CexIOAccountServiceRaw pollingService = (CexIOAccountServiceRaw) cexioExchange.getPollingAccountService();
                    CexIOBalanceInfo account = pollingService.getCexIOAccountInfo();
                    GHashIOHashrate hashrate = pollingService.getHashrate();

                    CexIOBalance balanceBTC = account.getBalanceBTC();
                    if (balanceBTC != null)
                        btcBalance = balanceBTC.getAvailable().floatValue();

                    hashRate = hashrate.getLast15m().floatValue();

                    return true;
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
                return false;
            }
            return false;
        }

        private void setTapBehaviour(int appWidgetId, String poolKey, RemoteViews views)
        {
            PendingIntent pendingIntent;

            if (pref_tapToUpdate)
            {
                Intent intent = new Intent(this, MinerWidgetProvider.class);
                intent.setAction(Constants.REFRESH);
                pendingIntent = PendingIntent.getBroadcast(this, appWidgetId, intent, 0);
            }
            else
            {
                Intent intent = new Intent(this, MinerStatsActivity.class);
                Bundle tabSelection = new Bundle();
                tabSelection.putString("poolKey", poolKey);
                intent.putExtras(tabSelection);
                pendingIntent = PendingIntent.getActivity(this, appWidgetId, intent, PendingIntent.FLAG_UPDATE_CURRENT);
            }

            views.setOnClickPendingIntent(R.id.widgetMinerButton, pendingIntent);
        }

        public void updateWidgetTheme(RemoteViews views)
        {
            // set the color
            if (pref_enableWidgetCustomization)
            {
                views.setInt(R.id.minerwidget_layout, "setBackgroundColor", pref_backgroundWidgetColor);
                views.setTextColor(R.id.widgetMinerHashrate, pref_mainWidgetTextColor);
                views.setTextColor(R.id.widgetMiner, pref_mainWidgetTextColor);
                views.setTextColor(R.id.refreshtime, pref_widgetRefreshSuccessColor);
                views.setTextColor(R.id.widgetBTCPayout, pref_secondaryWidgetTextColor);
            }
            else
            {
                views.setInt(R.id.minerwidget_layout, "setBackgroundColor", getResources().getColor(R.color.widgetBackgroundColor));
                views.setTextColor(R.id.widgetMinerHashrate, getResources().getColor(R.color.widgetMainTextColor));
                views.setTextColor(R.id.widgetMiner, getResources().getColor(R.color.widgetMainTextColor));
                views.setTextColor(R.id.widgetBTCPayout, Color.LTGRAY);
                views.setTextColor(R.id.refreshtime, Color.GREEN);
            }
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId)
        {
            super.onStartCommand(intent, flags, startId);
            return START_STICKY;
        }

        @Override
        public void onHandleIntent(Intent intent)
        {
            buildUpdate();
        }
    }
}
