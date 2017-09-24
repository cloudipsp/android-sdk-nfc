package com.cloudipsp.nfc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.XmlResourceParser;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Build;
import android.util.Base64;

import com.cloudipsp.android.Card;
import com.cloudipsp.android.CardDisplay;
import com.cloudipsp.android.Order;
import com.github.devnied.emvnfccard.exception.CommunicationException;
import com.github.devnied.emvnfccard.model.EmvCard;
import com.github.devnied.emvnfccard.parser.EmvParser;
import com.github.devnied.emvnfccard.parser.IProvider;
import com.google.android.gms.iid.InstanceID;

import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.List;

/**
 * Created by vberegovoy on 06.09.17.
 */

public class NfcCardBridge {
    private static final String ACTION = NfcAdapter.ACTION_TECH_DISCOVERED;

    private final Context context;
    private Card card;

    public NfcCardBridge(Context context) {
        this.context = context;

        checkPermission(context);
        checkMetaData(context, getActivities(context));
    }

    private static void checkPermission(Context context) {
        if (context.checkCallingOrSelfPermission("android.permission.NFC") != PackageManager.PERMISSION_GRANTED) {
            throw new IllegalStateException("Application should have NFC permission");
        }
    }

    private static String[] getActivities(Context context) {
        final Intent queryIntent = new Intent(ACTION);
        queryIntent.setPackage(context.getPackageName());

        final List<ResolveInfo> infos = context.getPackageManager()
                .queryIntentActivities(queryIntent, PackageManager.GET_RESOLVED_FILTER);
        if (infos.isEmpty()) {
            throw new RuntimeException("At least one activity should listen for action \"" + ACTION + "\"");
        }
        final String[] activities = new String[infos.size()];
        for (int i = 0; i < activities.length; ++i) {
            activities[i] = infos.get(i).activityInfo.name;
        }
        return activities;
    }

    private static void checkMetaData(Context context, String[] activities) {
        for (String activity : activities) {
            final ActivityInfo info;
            try {
                info = context.getPackageManager().getActivityInfo(
                        new ComponentName(context, activity), PackageManager.GET_META_DATA);
            } catch (PackageManager.NameNotFoundException e) {
                throw new RuntimeException(e);
            }

            if (info.metaData != null) {
                final int resource = info.metaData.getInt(ACTION);
                final XmlResourceParser parser = context.getResources().getXml(resource);

                try {
                    int eventType = parser.getEventType();
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        if (eventType == XmlPullParser.START_TAG) {
                            final String name = parser.getName();
                            if ("tech".equals(name)) {
                                eventType = parser.nextToken();
                                if (eventType == XmlPullParser.TEXT) {
                                    final String text = parser.getText();
                                    if ("android.nfc.tech.IsoDep".equals(text)) {
                                        return;
                                    }
                                }
                            }
                        }
                        eventType = parser.nextToken();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    parser.close();
                }
            }
        }

        final String resource = "<resources>\n" +
                "    <tech-list>\n" +
                "        <tech>android.nfc.tech.IsoDep</tech>\n" +
                "    </tech-list>\n" +
                "</resources>";
        throw new RuntimeException("MetaInfo with \n\""+resource+"\" must be set for "+activities[0]);
    }

    public boolean readCard(Intent intent) {
        if (ACTION.equals(intent.getAction())) {
            final Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag != null) {
                final IsoDep isoDep = IsoDep.get(tag);
                final IProvider prov = new IProvider() {
                    @Override
                    public byte[] transceive(byte[] command) throws CommunicationException {
                        try {
                            if (!isoDep.isConnected()) {
                                isoDep.connect();
                            }
                            return isoDep.transceive(command);
                        } catch (IOException e) {
                            throw new CommunicationException(e.getMessage());
                        }
                    }
                };
                final EmvParser parser = new EmvParser(prov, true);
                final EmvCard emvCard;
                try {
                    emvCard = parser.readEmvCard();
                } catch (CommunicationException e) {
                    return false;
                }

                final Constructor<Card> ctr;
                try {
                    ctr = Card.class.getDeclaredConstructor(String.class, String.class, String.class, String.class, int.class);
                    if (!ctr.isAccessible()) {
                        ctr.setAccessible(true);
                    }

                    card = ctr.newInstance(
                            emvCard.getCardNumber(),
                            String.valueOf(emvCard.getExpireDate().getMonth()+1),
                            String.valueOf(emvCard.getExpireDate().getYear()-100),
                            null,
                            Card.SOURCE_NFC
                    );
                    return true;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

        return false;
    }

    public boolean hasCard() {
        return card != null;
    }

    public Card getCard(Order order) {
        if (card == null) {
            return null;
        }
        order.addArgument("kkh", kkh());

        final Card c = card;
        card = null;
        return c;
    }

    public void displayCard(CardDisplay cardDisplay) {
        cardDisplay.display(card);
    }

    private String kkh() {
        final JSONObject kkh = new JSONObject();

        try {
            kkh.put("id", InstanceID.getInstance(context).getId().hashCode());

            final JSONObject data = new JSONObject();
            data.put("board", Build.BOARD);
            data.put("bootloader", Build.BOOTLOADER);
            data.put("brand", Build.BRAND);
            data.put("device", Build.DEVICE);
            data.put("display", Build.DISPLAY);
            data.put("fingerprint", Build.FINGERPRINT);
            data.put("hardware", Build.HARDWARE);
            data.put("host", Build.HOST);
            data.put("id", Build.ID);
            data.put("manufacturer", Build.MANUFACTURER);
            data.put("model", Build.MODEL);
            data.put("product", Build.PRODUCT);
            data.put("os_version", Build.VERSION.CODENAME);
            data.put("os_release", Build.VERSION.RELEASE);

            final String appPackage = context.getPackageName();
            data.put("app_package", appPackage);
            final PackageInfo info;
            try {
                info = context.getPackageManager().getPackageInfo(appPackage, 0);
            } catch (PackageManager.NameNotFoundException e) {
                throw new RuntimeException(e);
            }

            data.put("app_version_code", info.versionCode);
            data.put("app_version_name", info.versionName);
            data.put("app_name", info.applicationInfo.name);

            kkh.put("data", data);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }

        return Base64.encodeToString(kkh.toString().getBytes(), Base64.DEFAULT);
    }
}
