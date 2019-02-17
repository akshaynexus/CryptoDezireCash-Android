package cryptodezirecash.org.cryptodezirecashwallet;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.support.v4.content.FileProvider;

import org.acra.ACRA;
import org.acra.ReportingInteractionMode;
import org.acra.annotation.ReportsCrashes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.android.LogcatAppender;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import global.ContextWrapper;
import global.WalletConfiguration;
import global.utils.Io;
import pivtrum.NetworkConf;
import pivtrum.PivtrumPeerData;
import cryptodezirecash.org.cryptodezirecashwallet.contacts.ContactsStore;
import cryptodezirecash.org.cryptodezirecashwallet.module.CryptoDezireCashContext;
import cryptodezirecash.org.cryptodezirecashwallet.module.wallet.WalletBackupHelper;
import global.CryptoDezireCashModule;
import global.CryptoDezireCashModuleImp;
import cryptodezirecash.org.cryptodezirecashwallet.module.WalletConfImp;
import cryptodezirecash.org.cryptodezirecashwallet.rate.db.RateDb;
import cryptodezirecash.org.cryptodezirecashwallet.service.CryptoDezireCashWalletService;
import cryptodezirecash.org.cryptodezirecashwallet.utils.AppConf;
import cryptodezirecash.org.cryptodezirecashwallet.utils.CentralFormats;
import cryptodezirecash.org.cryptodezirecashwallet.utils.CrashReporter;

import static cryptodezirecash.org.cryptodezirecashwallet.service.IntentsConstants.ACTION_RESET_BLOCKCHAIN;
import static cryptodezirecash.org.cryptodezirecashwallet.utils.AndroidUtils.shareText;

/**
 * Created by mati on 18/04/17.
 */
@ReportsCrashes(
        mailTo = CryptoDezireCashContext.REPORT_EMAIL, // my email here
        mode = ReportingInteractionMode.TOAST,
        resToastText = R.string.crash_toast_text)
public class CryptoDezireCashApplication extends Application implements ContextWrapper {

    private static Logger log;

    /** Singleton */
    private static CryptoDezireCashApplication instance;
    public static final long TIME_CREATE_APPLICATION = System.currentTimeMillis();
    private long lastTimeRequestBackup;

    private CryptoDezireCashModule cryptodezirecashModule;
    private AppConf appConf;
    private NetworkConf networkConf;

    private CentralFormats centralFormats;

    private ActivityManager activityManager;
    private PackageInfo info;

    public static CryptoDezireCashApplication getInstance() {
        return instance;
    }

    private CrashReporter.CrashListener crashListener = new CrashReporter.CrashListener() {
        @Override
        public void onCrashOcurred(Thread thread, Throwable throwable) {
            log.error("crash occured..");
            throwable.printStackTrace();
            String authorities = "cryptodezirecash.org.cryptodezirecashwallet.myfileprovider";
            final File cacheDir = getCacheDir();
            // show error report dialog to send the crash
            final ArrayList<Uri> attachments = new ArrayList<Uri>();
            try {
                final File logDir = getDir("log", Context.MODE_PRIVATE);

                for (final File logFile : logDir.listFiles()) {
                    final String logFileName = logFile.getName();
                    final File file;
                    if (logFileName.endsWith(".log.gz"))
                        file = File.createTempFile(logFileName.substring(0, logFileName.length() - 6), ".log.gz", cacheDir);
                    else if (logFileName.endsWith(".log"))
                        file = File.createTempFile(logFileName.substring(0, logFileName.length() - 3), ".log", cacheDir);
                    else
                        continue;

                    final InputStream is = new FileInputStream(logFile);
                    final OutputStream os = new FileOutputStream(file);

                    Io.copy(is, os);

                    os.close();
                    is.close();

                    attachments.add(FileProvider.getUriForFile(getApplicationContext(), authorities, file));
                }
            } catch (final IOException x) {
                log.info("problem writing attachment", x);
            }
            shareText(CryptoDezireCashApplication.this,"CryptoDezireCash wallet crash", "Unexpected crash", attachments);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        try {
            initLogging();
            log = LoggerFactory.getLogger(CryptoDezireCashApplication.class);
            PackageManager manager = getPackageManager();
            info = manager.getPackageInfo(this.getPackageName(), 0);
            activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);

            //Bugsee.launch(this, "9b3473f1-984c-4f70-9aef-b0cf485839fd");

            // The following line triggers the initialization of ACRA
            ACRA.init(this);
            //if (BuildConfig.DEBUG)
            //    new ANRWatchDog().start();
            CrashReporter.init(getCacheDir());
            CrashReporter.setCrashListener(crashListener);
            // Default network conf for localhost test
            networkConf = new NetworkConf();
            appConf = new AppConf(getSharedPreferences(AppConf.PREFERENCE_NAME, MODE_PRIVATE));
            centralFormats = new CentralFormats(appConf);
            WalletConfiguration walletConfiguration = new WalletConfImp(getSharedPreferences("cryptodezirecash_wallet",MODE_PRIVATE));
            //todo: add this on the initial wizard..
            //walletConfiguration.saveTrustedNode(HardcodedConstants.TESTNET_HOST,0);
            //AddressStore addressStore = new SnappyStore(getDirPrivateMode("address_store").getAbsolutePath());
            ContactsStore contactsStore = new ContactsStore(this);
            cryptodezirecashModule = new CryptoDezireCashModuleImp(this, walletConfiguration,contactsStore,new RateDb(this),new WalletBackupHelper());
            cryptodezirecashModule.start();

        } catch (Exception e){
            e.printStackTrace();
        }
    }

    public void startCryptoDezireCashService() {
        Intent intent = new Intent(this,CryptoDezireCashWalletService.class);
        startService(intent);
    }

    private void initLogging() {
        final File logDir = getDir("log", MODE_PRIVATE);
        final File logFile = new File(logDir, "app.log");
        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        final PatternLayoutEncoder filePattern = new PatternLayoutEncoder();
        filePattern.setContext(context);
        filePattern.setPattern("%d{HH:mm:ss,UTC} [%thread] %logger{0} - %msg%n");
        filePattern.start();

        final RollingFileAppender<ILoggingEvent > fileAppender = new RollingFileAppender<ILoggingEvent>();
        fileAppender.setContext(context);
        fileAppender.setFile(logFile.getAbsolutePath());

        final TimeBasedRollingPolicy<ILoggingEvent> rollingPolicy = new TimeBasedRollingPolicy<ILoggingEvent>();
        rollingPolicy.setContext(context);
        rollingPolicy.setParent(fileAppender);
        rollingPolicy.setFileNamePattern(logDir.getAbsolutePath() + "/wallet.%d{yyyy-MM-dd,UTC}.log.gz");
        rollingPolicy.setMaxHistory(7);
        rollingPolicy.start();

        fileAppender.setEncoder(filePattern);
        fileAppender.setRollingPolicy(rollingPolicy);
        fileAppender.start();

        final PatternLayoutEncoder logcatTagPattern = new PatternLayoutEncoder();
        logcatTagPattern.setContext(context);
        logcatTagPattern.setPattern("%logger{0}");
        logcatTagPattern.start();

        final PatternLayoutEncoder logcatPattern = new PatternLayoutEncoder();
        logcatPattern.setContext(context);
        logcatPattern.setPattern("[%thread] %msg%n");
        logcatPattern.start();

        final LogcatAppender logcatAppender = new LogcatAppender();
        logcatAppender.setContext(context);
        logcatAppender.setTagEncoder(logcatTagPattern);
        logcatAppender.setEncoder(logcatPattern);
        logcatAppender.start();

        final ch.qos.logback.classic.Logger log = context.getLogger(Logger.ROOT_LOGGER_NAME);
        log.addAppender(fileAppender);
        log.addAppender(logcatAppender);
        log.setLevel(Level.INFO);
    }

    public CryptoDezireCashModule getModule(){
        return cryptodezirecashModule;
    }

    public AppConf getAppConf(){
        return appConf;
    }

    @Override
    public FileOutputStream openFileOutputPrivateMode(String name) throws FileNotFoundException {
        return openFileOutput(name,MODE_PRIVATE);
    }

    @Override
    public File getDirPrivateMode(String name) {
        return getDir(name,MODE_PRIVATE);
    }

    @Override
    public InputStream openAssestsStream(String name) throws IOException {
        return getAssets().open(name);
    }

    @Override
    public boolean isMemoryLow() {
        final int memoryClass = activityManager.getMemoryClass();
        return memoryClass<=cryptodezirecashModule.getConf().getMinMemoryNeeded();
    }

    @Override
    public String getVersionName() {
        return info.versionName;
    }

    @Override
    public void stopBlockchain() {
        Intent intent = new Intent(this,CryptoDezireCashWalletService.class);
        intent.setAction(ACTION_RESET_BLOCKCHAIN);
        startService(intent);
    }

    public NetworkConf getNetworkConf() {
        return networkConf;
    }

    /**
     *
     * @param trustedServer
     */
    public void setTrustedServer(PivtrumPeerData trustedServer) {
        networkConf.setTrustedServer(trustedServer);
        if (trustedServer.getHost() !=null)
        cryptodezirecashModule.getConf().saveTrustedNode(trustedServer.getHost(),35601);
        else
            cryptodezirecashModule.getConf().saveTrustedNode("seed01.cryptodezirecash.com",35601);
        appConf.saveTrustedNode(trustedServer);
    }

    public CentralFormats getCentralFormats() {
        return centralFormats;
    }

    public PackageInfo getPackageInfo() {
        return info;
    }

    public static long getTimeCreateApplication() {
        return TIME_CREATE_APPLICATION;
    }

    public long getLastTimeRequestedBackup() {
        return lastTimeRequestBackup;
    }

    public void setLastTimeBackupRequested(long lastTimeBackupRequested) {
        this.lastTimeRequestBackup = lastTimeBackupRequested;
    }

}
