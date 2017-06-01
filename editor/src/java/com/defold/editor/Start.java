package com.defold.editor;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FalseFileFilter;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.rolling.*;
import ch.qos.logback.core.util.FileSize;

import com.defold.editor.Updater.PendingUpdate;
import com.defold.libs.ResourceUnpacker;
import com.defold.util.SupportPath;

import javafx.application.Application;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.stage.Stage;


public class Start extends Application {

    private static Logger logger = LoggerFactory.getLogger(Start.class);

    static ArrayList<URL> extractURLs(String classPath) {
        ArrayList<URL> urls = new ArrayList<>();
        for (String s : classPath.split(File.pathSeparator)) {
            String suffix = "";
            if (!s.endsWith(".jar")) {
                suffix = "/";
            }
            URL url;
            try {
                url = new URL(String.format("file:%s%s", s, suffix));
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
            urls.add(url);
        }
        return urls;
    }

    public PendingUpdate getPendingUpdate() {
        return this.pendingUpdate.get();
    }

    private LinkedBlockingQueue<Object> pool;
    private ThreadPoolExecutor threadPool;
    private AtomicReference<PendingUpdate> pendingUpdate;
    private Timer updateTimer;
    private Updater updater;
    private static boolean createdFromMain = false;
    private final int firstUpdateDelay = 1000;
    private final int updateDelay = 60000;

    public Start() throws IOException {
        pool = new LinkedBlockingQueue<>(1);
        threadPool = new ThreadPoolExecutor(1, 1, 3000, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        threadPool.allowCoreThreadTimeOut(true);
        pendingUpdate = new AtomicReference<>();

        installUpdater();
    }

    private void installUpdater() throws IOException {
        String updateUrl = System.getProperty("defold.update.url");
        if (updateUrl != null && !updateUrl.isEmpty()) {
            logger.debug("automatic updates enabled");
            String resourcesPath = System.getProperty("defold.resourcespath");
            String sha1 = System.getProperty("defold.sha1");
            if (resourcesPath != null && sha1 != null) {
                updater = new Updater(updateUrl, resourcesPath, sha1);
                updateTimer = new Timer();
                updateTimer.schedule(newCheckForUpdateTask(), firstUpdateDelay);
            } else {
                logger.error(String.format("automatic updates could not be enabled with resourcespath='%s' and sha1='%s'", resourcesPath, sha1));
            }
        } else {
            logger.debug(String.format("automatic updates disabled (defold.update.url='%s')", updateUrl));
        }
    }

    private TimerTask newCheckForUpdateTask() {
        return new TimerTask() {
            @Override
            public void run() {
                try {
                    logger.debug("checking for updates");
                    PendingUpdate update = updater.check();
                    if (update != null) {
                        pendingUpdate.compareAndSet(null, update);
                    } else {
                        updateTimer.schedule(newCheckForUpdateTask(), updateDelay);
                    }
                } catch (IOException e) {
                    logger.debug("update check failed", e);
                }
            }
        };
    }

    private ClassLoader makeClassLoader() {
        ArrayList<URL> urls = extractURLs(System.getProperty("java.class.path"));
        // The "boot class-loader", i.e. for java.*, sun.*, etc
        ClassLoader parent = ClassLoader.getSystemClassLoader();
        // Per instance class-loader
        ClassLoader classLoader = new URLClassLoader(urls.toArray(new URL[urls.size()]), parent);
        return classLoader;
    }

    private Object makeEditor() throws Exception {
        ClassLoader classLoader = makeClassLoader();

        // NOTE: Is context classloader required?
        // Thread.currentThread().setContextClassLoader(classLoader);

        Class<?> editorApplicationClass = classLoader.loadClass("com.defold.editor.EditorApplication");
        Object editorApplication = editorApplicationClass.getConstructor(new Class[] { Object.class, ClassLoader.class }).newInstance(this, classLoader);
        return editorApplication;
    }

    private void poolEditor(long delay) {
        FutureTask<Object> future = new FutureTask<>(new Callable<Object>() {

            @Override
            public Object call() throws Exception {
                // Arbitrary sleep in order to reduce the CPU load while loading the project
                Thread.sleep(delay);
                Object editorApplication = makeEditor();
                pool.add(editorApplication);
                return null;
            }

        });
        threadPool.submit(future);
    }

    public void openEditor(String[] args) throws Exception {
        if (!createdFromMain) {
            throw new RuntimeException(String.format("Multiple %s classes. ClassLoader errors?", this.getClass().getName()));
        }
        poolEditor(3000);
        Object editorApplication = pool.take();
        Method run = editorApplication.getClass().getMethod("run", new Class[]{ String[].class });
        run.invoke(editorApplication, new Object[] { args });
    }

    private void kickLoading(Splash splash) {
        threadPool.submit(() -> {
            try {
                // A terrible hack as an attempt to avoid a deadlock when loading native libraries
                // Prism might be loading native libraries at this point, although we kick this loading after the splash has been shown.
                // The current hypothesis is that the splash is "onShown" before the loading has finished and rendering can start.
                // Occular inspection shows the splash as grey for a few frames (1-3?) before filled in with graphics. That grey-time also seems to differ between runs.
                // This is an attempt to make the deadlock less likely to happen and hopefully avoid it altogether. No guarantees.
                Thread.sleep(200);
                ResourceUnpacker.unpackResources();
                ClassLoader parent = ClassLoader.getSystemClassLoader();
                Class<?> glprofile = parent.loadClass("com.jogamp.opengl.GLProfile");
                Method init = glprofile.getMethod("initSingleton");
                init.invoke(null);
            } catch (Throwable t) {
                logger.error("failed to extract native libs", t);
            }
            try {
                pool.add(makeEditor());
                javafx.application.Platform.runLater(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            List<String> params = getParameters().getRaw();
                            openEditor(params.toArray(new String[params.size()]));
                            splash.close();
                        } catch (Throwable t) {
                            t.printStackTrace();
                        }
                    }
                });
            } catch (Throwable t) {
                t.printStackTrace();
                String message = (t instanceof InvocationTargetException) ? t.getCause().getMessage() : t.getMessage();
                javafx.application.Platform.runLater(() -> {
                    splash.setLaunchError(message);
                    splash.setErrorShowing(true);
                });
            }
            return null;
        });
    }

    private void prunePackages() {
        String sha1 = System.getProperty("defold.sha1");
        String resourcesPath = System.getProperty("defold.resourcespath");
        if (sha1 != null && resourcesPath != null) {
            try {
                File dir = new File(resourcesPath, "packages");
                if (dir.exists() && dir.isDirectory()) {
                    Collection<File> files = FileUtils.listFiles(dir, new WildcardFileFilter("defold-*.jar"), FalseFileFilter.FALSE);
                    for (File f : files) {
                        if (!f.getName().contains(sha1)) {
                            f.delete();
                        }
                    }
                }
            } catch (Throwable t) {
                logger.error("could not prune packages", t);
            }
        }
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        /*
          Note
          Don't remove

          Background
          Before the mysterious line below Command-H on OSX would open a generic Java about dialog instead of hiding the application.
          The hypothosis is that awt must be initialized before JavaFX and in particular on the main thread as we're pooling stuff using
          a threadpool.
          Something even more mysterious is that if the construction of the buffered image is moved to "static void main(.." we get a null pointer in
          clojure.java.io/resource..
        */

        BufferedImage tmp = new BufferedImage(1, 1, BufferedImage.TYPE_3BYTE_BGR);

        // Clean up old packages as they consume a lot of hard drive space.
        // NOTE! This is a temp hack to give some hard drive space back to users.
        // The proper fix would be an upgrade feature where users can upgrade and downgrade as desired.
        prunePackages();

        final Splash splash = new Splash();
        splash.shownProperty().addListener(new ChangeListener<Boolean>() {
            @Override
            public void changed(ObservableValue<? extends Boolean> observable,
                    Boolean oldValue, Boolean newValue) {
                if (newValue.booleanValue()) {
                    kickLoading(splash);
                }
            }
        });
        splash.show();
    }

    @Override
    public void stop() throws Exception {
        // NOTE: We force exit here as it seems like the shutdown process
        // is waiting for all non-daemon threads to terminate, e.g. clojure agent thread
        System.exit(0);
    }

    public static void main(String[] args) throws Exception {
        createdFromMain = true;
        initializeLogging();
        Start.launch(args);
    }

    private static void initializeLogging() {
        Path logDirectory = Editor.getLogDirectory();

        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

        RollingFileAppender appender = new RollingFileAppender();
        appender.setName("FILE");
        appender.setAppend(true);
        appender.setPrudent(true);
        appender.setContext(root.getLoggerContext());

        TimeBasedRollingPolicy rollingPolicy = new TimeBasedRollingPolicy();
        rollingPolicy.setMaxHistory(30);
        rollingPolicy.setFileNamePattern(logDirectory.resolve("editor2.%d{yyyy-MM-dd}.log").toString());
        rollingPolicy.setTotalSizeCap(FileSize.valueOf("1GB"));
        rollingPolicy.setContext(root.getLoggerContext());
        rollingPolicy.setParent(appender);
        appender.setRollingPolicy(rollingPolicy);
        rollingPolicy.start();

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} %-4relative [%thread] %-5level %logger{35} - %msg%n");
        encoder.setContext(root.getLoggerContext());
        encoder.start();

        appender.setEncoder(encoder);
        appender.start();

        root.addAppender(appender);
    }

}
