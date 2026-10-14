package tcb.engine;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import forge.gamemodes.match.HostedMatch;
import forge.gui.GuiBase;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.model.FModel;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;
import org.jupnp.UpnpServiceConfiguration;

/**
 * One-time headless initialisation of Forge: card database, formats, preferences.
 * Forge resolves everything relative to its forge-gui directory (which contains res/).
 */
public final class ForgeEnv {
    private static boolean initialized;

    private ForgeEnv() {
    }

    /** Initialise using -Dtcb.forgeGuiDir, $TCB_FORGE_GUI, or ./forge/forge-gui. */
    public static synchronized void init() {
        init(defaultForgeGuiDir());
    }

    public static synchronized void init(Path forgeGuiDir) {
        if (initialized) {
            return;
        }
        if (!Files.isDirectory(forgeGuiDir.resolve("res/cardsfolder"))) {
            throw new IllegalStateException("Forge resources not found under " + forgeGuiDir
                    + " (set -Dtcb.forgeGuiDir or TCB_FORGE_GUI to the forge/forge-gui directory)");
        }
        System.setProperty("java.awt.headless", "true");
        // Forge relies on this to avoid "Comparison method violates its general contract!" crashes
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        GuiBase.setInterface(new HeadlessGui(forgeGuiDir.toAbsolutePath().normalize()));
        FModel.initialize(null, null);
        initialized = true;
    }

    public static Path defaultForgeGuiDir() {
        String prop = System.getProperty("tcb.forgeGuiDir");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        String env = System.getenv("TCB_FORGE_GUI");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        return Path.of("forge", "forge-gui");
    }

    /** Minimal IGuiBase: no windows, no images, no sound. Adapted from Forge's ForgeMatrixWriter tool. */
    private static final class HeadlessGui implements IGuiBase {
        private final String assetsDir;

        private HeadlessGui(Path forgeGuiDir) {
            this.assetsDir = forgeGuiDir + File.separator;
        }

        @Override public boolean isRunningOnDesktop() { return true; }
        @Override public boolean isLibgdxPort() { return false; }
        @Override public String getCurrentVersion() { return "3cb-headless"; }
        @Override public void invokeInEdtNow(Runnable runnable) { runnable.run(); }
        @Override public void invokeInEdtLater(Runnable runnable) { runnable.run(); }
        @Override public void invokeInEdtAndWait(Runnable proc) { proc.run(); }
        @Override public void runBackgroundTask(String message, Runnable task) { task.run(); }
        @Override public boolean isGuiThread() { return true; }
        @Override public String getAssetsDir() { return assetsDir; }
        @Override public ImageFetcher getImageFetcher() { return null; }
        @Override public ISkinImage getSkinIcon(FSkinProp skinProp) { return null; }
        @Override public ISkinImage getUnskinnedIcon(String path) { return null; }
        @Override public ISkinImage getCardArt(PaperCard card, boolean backFace) { return null; }
        @Override public ISkinImage createLayeredImage(PaperCard card, FSkinProp background, String overlayFilename, float opacity) { return null; }
        @Override public void clearImageCache() { }
        @Override public String encodeSymbols(String str, boolean formatReminderText) { return str; }
        @Override public int getAvatarCount() { return 0; }
        @Override public int getSleevesCount() { return 0; }
        @Override public float getScreenScale() { return 1.0f; }
        @Override public void preventSystemSleep(boolean preventSleep) { }
        @Override public void download(GuiDownloadService service, Consumer<Boolean> callback) { callback.accept(false); }
        @Override public void copyToClipboard(String text) { }
        @Override public void browseToUrl(String url) throws IOException, URISyntaxException { }
        @Override public void showCardList(String title, String message, List<PaperCard> list) { }
        @Override public boolean showBoxedProduct(String title, String message, List<PaperCard> list) { return false; }
        @Override public void showBugReportDialog(String title, String text, boolean showExitAppBtn) { }
        @Override public void showImageDialog(ISkinImage image, String message, String title) { }
        @Override public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) { return defaultOption; }
        @Override public String showInputDialog(String message, String title, FSkinProp icon, String initialInput, List<String> inputOptions, boolean isNumeric) { return initialInput; }
        @Override public String showFileDialog(String title, String defaultDir) { return defaultDir; }
        @Override public File getSaveFile(File defaultFile) { return defaultFile; }
        @Override public <T> List<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax, List<T> sourceChoices, List<T> destChoices) { return destChoices; }
        @Override public <T> List<T> getChoices(String message, int min, int max, Collection<T> choices, Collection<T> selected, FSerializableFunction<T, String> display) { return new ArrayList<>(selected); }
        @Override public PaperCard chooseCard(String title, String message, List<PaperCard> list) { return list.isEmpty() ? null : list.get(0); }
        @Override public boolean isSupportedAudioFormat(File file) { return false; }
        @Override public IAudioClip createAudioClip(String filename) { return null; }
        @Override public IAudioMusic createAudioMusic(String filename) { return null; }
        @Override public void startAltSoundSystem(String filename, boolean isSynchronized) { }
        @Override public void showSpellShop() { }
        @Override public void showBazaar() { }
        @Override public IGuiGame getNewGuiGame() { return null; }
        @Override public HostedMatch hostMatch() { return null; }
        @Override public UpnpServiceConfiguration getUpnpPlatformService() { return null; }
        @Override public boolean hasNetGame() { return false; }
    }
}
